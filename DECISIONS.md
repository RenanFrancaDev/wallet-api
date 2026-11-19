# Decisões técnicas

Registro do que escolhi, por quê, o que descartei e o que faria com mais tempo. O documento cresce a cada etapa do projeto.

## 1. Regras de consistência dentro do banco

**Decisão:** validação, controle de concorrência, idempotência e a regra de saldo ficam no `PKG_WALLET` (PL/SQL). A aplicação Java só chama o package e traduz erros.

**Por quê:** a garantia vale para qualquer cliente, inclusive SQL executado direto. O banco é o único ponto que enxerga todas as conexões ao mesmo tempo.

## 2. Três schemas e privilégio mínimo

**Decisão:** `WALLET_OWNER` (dono dos objetos, usado pelas migrations), `WALLET_APP` (usuário da aplicação) e `WALLET_AUDIT` (trilha).

- O `WALLET_APP` recebe só `CREATE SESSION`, `EXECUTE` no `PKG_WALLET` e `SELECT` em duas views. Nenhum DML em tabela.
- O package usa `AUTHID DEFINER`: roda com os direitos do dono, então o `WALLET_APP` não precisa de acesso às tabelas.
- Todos os grants estão em migrations; nada é criado à mão. Os usuários (que precisam de privilégio de administrador) são criados no script de inicialização do container.
- Os privilégios são dados direto ao usuário, não via role: o Oracle ignora privilégios de role ao compilar PL/SQL.

**Descoberto testando do zero:** o `WALLET_AUDIT` precisava de `CREATE SEQUENCE`, que faltava no script de usuários.

## 3. Auditoria: procedure no schema de auditoria, não `INSERT` direto

**Decisão:** a trigger do `WALLET_OWNER` chama `WALLET_AUDIT.PKG_AUDIT.REGISTRAR_SALDO`. O OWNER tem só `EXECUTE`, nunca `INSERT` na tabela.

**Por quê:** com `INSERT`, quem controla o OWNER poderia gravar linhas falsas na trilha, com o momento que quisesse. Com a procedure, o OWNER só acrescenta pela porta oficial, e o momento (`SYSTIMESTAMP`) e o usuário são preenchidos pelo próprio schema de auditoria. Como bônus, a tabela pode mudar sem quebrar a trigger.

**Limite honesto:** o OWNER ainda pode chamar a procedure com valores falsos. O ganho é que ele não edita, não apaga e não escolhe o momento. Em produção eu complementaria com a auditoria nativa do Oracle.

**Sem chave estrangeira** da trilha para `CARTEIRA`: a trilha é independente e não deve depender (nem dar privilégio) ao schema que ela audita.

## 4. Idempotência: a chave mora no lançamento

**Decisão:** `LANCAMENTO.CHAVE_IDEMPOTENCIA` com `UNIQUE (CARTEIRA_ID, CHAVE_IDEMPOTENCIA)`. Não há tabela separada de chaves.

**Por quê:** toda operação aplicada já é uma linha do extrato, com tipo e valor. A chave na mesma linha e na mesma transação elimina o risco de gravar a chave e falhar o lançamento, e é uma tabela a menos.

**Comportamento:** mesma chave com mesmo tipo e valor devolve o resultado original sem tocar no saldo (`repetida = 1`); mesma chave com tipo ou valor diferente é conflito (`-20004`).

**Custo:** um débito recusado por saldo insuficiente não deixa rastro de chave, então um retry reavalia o saldo. Isso é coerente, pois a operação nunca foi aplicada.

**Com mais tempo:** registrar também as tentativas recusadas, se houvesse necessidade de investigação.

## 5. Concorrência: lock pessimista por carteira

**Decisão:** `SELECT ... FOR UPDATE WAIT 5` na linha da carteira, no início de `REGISTRAR_OPERACAO`.

**Por quê:** operações na mesma carteira ficam em fila, e carteiras diferentes não se bloqueiam. O saldo é uma linha de alta contenção e a regra "débito não pode deixar saldo negativo" depende de ler o saldo atual; o lock garante que ninguém leia um saldo que está prestes a mudar.

**Ordem importa:** o lock vem **antes** da checagem de idempotência. Como a consulta da chave roda depois de obtido o lock, ela enxerga o que a transação anterior (que segurava o lock) já confirmou. Assim, duas chamadas iguais simultâneas nunca passam as duas como "novas". O `UNIQUE` fica como rede de segurança: se uma duplicata escapasse, o banco recusaria a segunda linha e a chamada falharia, sem aplicar em dobro.

**`WAIT 5`:** sem o `WAIT`, uma sessão presa seguraria as demais indefinidamente. Esgotados 5 s, o erro `-20006` (carteira ocupada) permite ao cliente tentar de novo.

**Alternativas descartadas:** lock otimista com coluna de versão (exigiria retry em toda colisão, e a carteira é um ponto quente) e `SERIALIZABLE` (mais caro e com erros de serialização para tratar).

**Prova:** o teste de concorrência (`db/tests/concorrencia_pkg_wallet.sh`) passa com o lock. Removendo o `FOR UPDATE`, o cenário 1 aplicou **20 débitos em vez de 10** e o extrato passou a somar -100 com saldo 0 (o `CHECK (SALDO >= 0)` impediu o saldo negativo, mas o extrato ficou incoerente). Ou seja, o teste detecta o defeito que o lock evita.

**Limite do teste:** as sessões `sqlplus` se sobrepõem, mas não há barreira que as faça colidir no mesmo instante. Por isso o teste de integração em Java (threads com barreira) é a prova mais forte e entra na etapa de Testcontainers. Nos cenários 2 e 3, sem o lock, o `UNIQUE` não chegou a ser disputado nesta execução: dependem de timing.

## 6. Quem controla a transação

**Decisão:** as procedures **não fazem `COMMIT` nem `ROLLBACK`**. A aplicação controla (`@Transactional`).

**Por quê:** a procedure fica composável (uma transferência futura poderia chamar duas operações na mesma transação) e testável (o teste pode dar `ROLLBACK` e não deixar sujeira). Uma chamada que termina em exceção não deixa efeito parcial, porque o Oracle desfaz as alterações daquela chamada.

**Custo:** quem chama precisa lembrar de confirmar a transação. Fica concentrado em um ponto da aplicação.

## 7. Sequences: `CACHE 20` e lacunas

**Decisão:** sequences explícitas, uma por tabela, `START WITH 1 INCREMENT BY 1 CACHE 20 NOCYCLE`.

**Comportamento relevante:** o Oracle reserva 20 números em memória por vez. Se o banco reiniciar, os reservados e não usados se perdem, e um `ROLLBACK` também consome número. Portanto **há lacunas**. É aceitável: o ID identifica a linha, não conta eventos. A ordem do extrato usa `CRIADO_EM` e `ID`.

**Alternativa:** `NOCACHE` não elimina lacunas (rollback ainda consome) e custa em concorrência.

## 8. Como a trigger de auditoria sabe qual operação mudou o saldo

**Decisão:** o package `PKG_WALLET_CTX` guarda, na sessão, o ID do lançamento em andamento. O `PKG_WALLET` o define antes de atualizar o saldo e o limpa depois (inclusive em caso de erro). A trigger lê esse valor.

**Por quê:** a trigger só enxerga a linha de `CARTEIRA`, e o enunciado pede "a operação que causou a alteração" na trilha.

**Efeito útil:** se alguém altera o saldo por SQL direto, o contexto está vazio e a trilha grava `OPERACAO_ID` nulo, o que sinaliza uma alteração fora do package.

**Risco:** com pool de conexões, o estado fica na conexão; por isso a limpeza obrigatória no tratamento de erro.

## 9. Validações que o tipo de dado não faz

`NUMBER(15,2)` arredonda em silêncio (10.005 viraria 10.01). O package rejeita valores com mais de 2 casas, acima do máximo do tipo, nulos, e chaves em branco ou com mais de 100 caracteres, todas com `-20002`. No Oracle, string vazia é `NULL`, então `TRIM` é aplicado onde a distinção importa.

## 10. Dois conjuntos de migrations

`audit` (executado como `WALLET_AUDIT`) e `owner` (como `WALLET_OWNER`), cada um com seu histórico do Flyway no próprio schema. Assim tudo fica versionado sem dar ao OWNER privilégio de criar objetos em outro schema. A ordem de execução é: `audit` primeiro.

## 11. Leitura por views

A aplicação lê por `V_CARTEIRA` e `V_EXTRATO`, não pelas tabelas. Limita as colunas expostas e permite mudar as tabelas sem quebrar a leitura. Escrever continua só pelo package.

## Limitações conhecidas

- A trigger de imutabilidade não pega `TRUNCATE` (DDL), e o dono do schema pode desabilitá-la. Em produção: auditoria nativa do Oracle e controle de quem tem privilégio de DDL.
- O OWNER pode chamar `PKG_AUDIT` com valores falsos (ver item 3).
- Um débito recusado não deixa rastro (ver item 4).

## O que eu faria com mais tempo

- Transferência entre carteiras em uma procedure (débito e crédito atômicos, com ordem fixa de lock por ID para evitar deadlock).
- Limite diário de débito resolvido no banco.
- Job de conciliação com `DBMS_SCHEDULER` (soma do extrato igual ao saldo; divergências na auditoria). O campo `SALDO_APOS` já ajuda a conferir.
- Auditoria nativa do Oracle (`AUDIT`) como camada extra além das triggers.
- Pipeline de CI (build e testes com Testcontainers).
