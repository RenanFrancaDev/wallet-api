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

## 12. Flyway executado pela aplicação, em dois conjuntos

**Decisão:** a classe `FlywayConfig` define dois beans `Flyway`, um por conjunto (`audit` e `owner`), cada um com o seu usuário, e o segundo depende do primeiro (`@DependsOn`). A autoconfiguração do Flyway do Spring fica desligada (`spring.flyway.enabled=false`).

**Por quê:** a autoconfiguração aceita um único usuário e um único conjunto de migrations. Aqui os objetos pertencem a schemas diferentes, e a ordem é obrigatória (a trigger do OWNER chama a procedure do schema de auditoria). Tudo roda no startup, então um clone novo chega ao mesmo banco sem passo manual.

**Separação de credenciais:** as credenciais do `WALLET_OWNER` e do `WALLET_AUDIT` são usadas só pelo Flyway no startup. As requisições usam o `WALLET_APP`, que não tem DML em tabelas.

**Contorno de um bug de Windows:** quando o Flyway monta a conexão sozinho (url, usuário e senha), o módulo Oracle procura um arquivo de log dentro do jar do driver e converte o caminho `nested:C:\...` com `Paths.get()`, o que lança `InvalidPathException` no Windows ao rodar o jar executável do Spring Boot. Entrego ao Flyway um `DriverManagerDataSource` pronto, e esse trecho deixa de ser executado. Não usei pool porque a migration roda uma única vez no startup. O erro vem da conversão de caminho do Windows; não testei em Linux nem macOS, mas o contorno não depende do sistema operacional.

**Alternativa descartada:** rodar o Flyway por fora (CLI ou plugin Maven) em um passo separado. Funcionaria, mas exigiria um passo manual a mais no README e separaria o ciclo de vida do banco do da aplicação.

## 13. Senhas no `.env`, lidas pela aplicação

`spring.config.import=optional:file:.env[.properties]` faz a aplicação ler as senhas do mesmo `.env` do `docker compose`, sem duplicar configuração. O arquivo é opcional: em container ou CI, as variáveis de ambiente fazem o mesmo papel. Nada é commitado.

## 14. Versões e o que o Initializr gerou

Usei a versão padrão do Spring Initializr no momento (Spring Boot 4.2.0), com Java 21 e Maven. O Initializr gerou três classes de teste de exemplo que sobem um Oracle com a tag `latest`; **não as mantive**, porque o enunciado proíbe `latest` e porque o nosso modelo exige os três usuários, que a imagem pura não cria. Os testes de integração entram na etapa própria, com a tag fixa.

## 15. Pacote Java e dependências

**Pacote** `br.com.renanfranca.walletapi` (convenção de domínio reverso), com subpacotes por responsabilidade (`config` agora; `controller`, `service` e `exception` nas próximas etapas).

**JDBC em vez de JPA/Hibernate:** toda escrita passa pelo `PKG_WALLET` e a leitura são views simples. JPA seria uma camada que esconde o SQL num serviço em que o banco é quem decide. O enunciado permite JDBC.

**Sem Lombok:** o código fica explícito e eu consigo explicar linha a linha; os DTOs podem ser `record` do próprio Java.

**Sem Spring Security:** a autenticação é uma API key simples (um filtro) e o enunciado exclui JWT/OAuth.

**Sem banco em memória** (H2): o enunciado proíbe, e a aplicação só fala com o Oracle.

## 16. Camadas e responsabilidades

`controller` (HTTP), `service` (caso de uso e transação), `repository` (banco), `model`, `dto` e `exception`. O controller só traduz HTTP para chamadas de serviço e de volta, sem regra de negócio. A regra de negócio mora no banco (item 1); o serviço existe para controlar a transação e orquestrar.

**Por quê não colocar tudo no controller:** a transação (`@Transactional`) precisa envolver a chamada ao package e a leitura seguinte, e isso é responsabilidade de um serviço. Também deixa o controller testável sem banco.

## 17. Traduzir erros em um único lugar

**Decisão:** o enum `ErroNegocio` liga o código do Oracle (`-20001` a `-20006`) ao `codigo` da API, ao status HTTP e à mensagem. O `GlobalExceptionHandler` usa esse catálogo tanto para erros que nascem no banco quanto para os detectados pela aplicação (por exemplo, `GET` de carteira inexistente), então a resposta é idêntica nos dois casos.

**Status HTTP:** `400` requisição inválida em si; `404` recurso inexistente; `409` conflito com o estado atual (chave de idempotência já usada, extrato imutável); `422` requisição bem formada que viola regra de negócio (saldo insuficiente); `503` falha transitória (carteira ocupada), que o cliente pode repetir.

**A mensagem do Oracle não vai para o cliente.** Nomes de objeto e stack vazam estrutura interna. Os erros de negócio saem com a mensagem do catálogo; o `-20002` acrescenta em `detalhes` o motivo exato, porque essa mensagem é escrita por nós no PL/SQL e é segura. Qualquer outro erro vira `500 ERRO_INTERNO` genérico, com o detalhe só no log.

**Formato único:** o payload de erro é o mesmo para erros de negócio, validação, JSON malformado, rota inexistente e método não permitido (`codigo`, `mensagem`, `timestamp`, `path`, `detalhes`).

## 18. Validação em duas camadas

O Bean Validation (`@Size` no titular) rejeita o corpo antes de ir ao banco e evita uma viagem. A regra definitiva continua no `PKG_WALLET`. As duas devolvem o mesmo `codigo` (`DADOS_INVALIDOS`), então o cliente não precisa saber qual camada barrou.

## 19. Transação na aplicação, não no banco

O `CarteiraService` é `@Transactional`: abre a transação ao entrar, confirma ao sair e desfaz se lançar exceção. O `PKG_WALLET` não faz `COMMIT` (item 6). O serviço também lê a carteira recém-criada na mesma transação, o que enxerga uma linha ainda não confirmada.

## 20. Saldo sempre com 2 casas decimais

O Oracle devolve `0` e `100.5`, não `0.00` e `100.50`. O `record Carteira` normaliza o saldo para 2 casas no construtor, e falha alto (`UNNECESSARY`) se o banco um dia devolver mais, porque isso seria bug. O contrato da API é "saldo em reais com 2 casas".

## 21. Criar carteira devolve `201` com `Location`

`POST /wallets` responde `201 Created` com o header `Location: /wallets/{id}` e o recurso no corpo, o padrão REST. O corpo da requisição é opcional, já que o titular é opcional pelo enunciado.

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
