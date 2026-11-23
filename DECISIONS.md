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

## 22. CI mínimo com merge bloqueado

**Decisão:** um workflow do GitHub Actions compila (com avisos do compilador tratados como erro) e roda os testes unitários em todo PR para `develop` e `main`. A proteção de branch exige o job `Build e testes`, então o merge só é possível com ele verde.

**"Typecheck" em Java:** o compilador já recusa tipo errado; o que se escolhe é o rigor. Ligar `-Xlint:all` com `failOnWarning` tornou o gate mais forte e já encontrou um problema real no primeiro uso (uma exceção serializável sem `serialVersionUID`), corrigido na mesma branch.

**Validado em checkout limpo, sem o `.env`**, para ter certeza de que o CI não depende de arquivos locais.

**Fora, de propósito:** linter e formatador. O Biome é do ecossistema JavaScript; em Java os equivalentes são o Spotless (formatador) e o Checkstyle (regras de estilo). Cada um é um plugin novo e reformataria código existente, gerando um PR grande. Fica para depois do núcleo.

**Com mais tempo:** os testes de integração com Testcontainers entram no mesmo workflow (o runner já tem Docker), formatador e linter, e cobertura com limite mínimo.

## 23. Formatação automática com google-java-format (Spotless)

**Decisão:** Spotless com google-java-format em estilo AOSP, exigido no `verify` e, portanto, no CI. Detalhes e motivos em [PADROES.md](PADROES.md).

**Por quê:** é o formatador mais usado em Java e não é configurável, o que elimina discussão de estilo. Escolhi o estilo AOSP (4 espaços) em vez do padrão Google (2 espaços) porque o código já usava 4 e isso reduziu o diff da adoção.

**Problema real na adoção:** no Windows, o Spotless convertia todos os arquivos para CRLF e o diff passava de mil linhas de puro ruído. Fixei LF no plugin (`lineEndings=UNIX`) e no `.gitattributes`. A reformatação ficou em um commit `style:` separado do commit que liga o plugin.

**Limite:** não suporta tabulação.

## 24. Lint com Checkstyle, conjunto enxuto e sem regras de formato

**Decisão:** Checkstyle com um arquivo de regras próprio (`config/checkstyle/checkstyle.xml`), exigido no `verify` e no CI. Detalhes e motivos em [PADROES.md](PADROES.md).

**Por que Checkstyle:** é o linter de estilo mais usado em Java. Não existe uma ferramenta única como o Biome (formatador e linter juntos); o comum é combinar um formatador (Spotless) com um linter (Checkstyle), cada um no seu papel.

**Por que um conjunto enxuto e não o preset do Google:** o preset exige indentação de 2 espaços e brigaria com o estilo AOSP já adotado; além disso, um preset grande gera centenas de avisos de Javadoc e acaba ignorado. Escolhi regras uma a uma, com o motivo registrado no próprio arquivo, e deixei **fora** tudo que é formato, para não haver duas ferramentas decidindo a mesma coisa.

**Falha o build, sem "aviso":** qualquer violação quebra o CI, porque aviso que ninguém lê vira dívida.

**Equivalente do `noConsole` do Biome:** proibido `System.out`, `System.err` e `printStackTrace`; o logger tem nível, formato e destino.

**Com mais tempo:** SpotBugs ou Error Prone para análise de bugs mais profunda, e Javadoc obrigatório só na API pública.

## 25. Pipeline em jobs encadeados e um portão final com nome fixo

**Decisão:** o CI deixou de ser um job único e passou a ter `Qualidade` (formato, lint e tipos), `Testes unitários`, `Build` e um job final `Build e testes`, encadeados com `needs:`.

**Por quê:** é o padrão dos pipelines dos projetos Node de referência (lint, typecheck, testes e build como etapas distintas). No PR se vê de relance se a falha é de formato, de lint, de teste ou de empacotamento, e a ordem economiza tempo de runner: não se rodam testes em código que não passa na qualidade.

**O portão final com nome fixo:** a proteção de branch exige só o check `Build e testes`. Esse job passa apenas se os outros três passarem. Assim, renomear ou acrescentar jobs não exige mexer na regra do GitHub; sem esse job, renomear um job exigido deixaria um check que nunca aparece e bloquearia todos os PRs.

**Hooks locais (opcionais):** `commit-msg` valida o padrão `tipo: descrição` e `pre-push` roda formato, lint e testes. São uma conveniência, não a barreira: quem não os ativa continua barrado pelo CI. Não dependem de Node (o equivalente ao Husky aqui é `core.hooksPath` apontando para uma pasta versionada).

**Não incluído ainda:** gate de cobertura (JaCoCo, com limite que só sobe, como nos projetos de referência) e os testes de integração com Testcontainers; eles entram nos jobs existentes.

## 26. Registrar operação: chave no header e `201` também na repetição

**Chave no header `Idempotency-Key`** (o padrão de mercado), obrigatório: ela identifica a *tentativa*, não faz parte do conteúdo da operação, e o mesmo corpo pode ser reenviado com a mesma chave. Sem o header, `400`.

**Repetição devolve `201` com o mesmo corpo**, marcada com o header `Idempotent-Replayed: true`. O enunciado diz que a segunda chamada "retorna o mesmo resultado da primeira", então devolvo o mesmo status e o mesmo corpo; quem precisar distinguir lê o header. A alternativa era responder `200` nas repetições, o que é comum, mas faria o retry de um cliente que só confere o status `201` parecer uma falha.

**Mesma chave com conteúdo diferente** é `409` (`CHAVE_IDEMPOTENCIA_CONFLITO`), decidido dentro do banco.

**Validação em duas camadas, com uma fonte da verdade para a chave:** o Bean Validation barra tipo ausente, valor `<= 0` e mais de 13 dígitos inteiros ou 2 decimais antes de ir ao banco. A chave vazia ou com mais de 100 caracteres é validada **só pelo banco**, que devolve `DADOS_INVALIDOS` com o motivo; duplicar a regra no Java criaria dois lugares para manter.

**Valor como `BigDecimal`**, nunca `double`, e mais de 2 casas é rejeitado em vez de arredondado em silêncio.

**A transação é do serviço**, e o lock da carteira dura até o `COMMIT` feito pelo Spring. Isso foi comprovado pela API com concorrência real (resultados no PDF e no PR): 10 débitos simultâneos de 10 sobre saldo 50 resultaram em 5 aplicados e 5 recusados; 10 chamadas iguais simultâneas aplicaram uma vez só.

**O lançamento devolvido é lido da view** `V_EXTRATO` depois da chamada ao package, em vez de usar o `OUT` de data e hora, para o fuso horário vir correto e a repetição devolver exatamente o lançamento original.

## 27. Extrato: paginação por deslocamento, ordem fixa e período semiaberto

**Paginação por `page` e `size` (deslocamento)**, com `totalItens` e `totalPaginas` na resposta. É o mais simples de usar e de entender, e atende ao "lista, com paginação" do enunciado. O banco recebe `OFFSET :deslocamento ROWS FETCH NEXT :tamanho ROWS ONLY`, com o deslocamento calculado como `página x tamanho`.

**Limite honesto:** o deslocamento fica mais lento quanto maior a página (o banco percorre e descarta as linhas anteriores) e pode repetir ou pular itens se entrarem lançamentos novos entre duas consultas. Para extratos enormes, a alternativa é a **paginação por cursor** (`WHERE (data, id) < (:ultimaData, :ultimoId)`), que o índice `IX_LANCAMENTO_EXTRATO (carteira, data, id)` já suportaria. Fica como o que eu faria com mais tempo.

**Ordem fixa, do mais recente para o mais antigo** (data e depois id, o desempate para lançamentos no mesmo instante), usando esse índice. Sem ordem explícita a paginação seria instável.

**Período `[from, to)`:** `from` inclusivo e `to` exclusivo. Intervalos semiabertos encadeiam sem sobrepor nem deixar buraco (o `to` de um é o `from` do próximo). Ambos são `OffsetDateTime` ISO-8601 com fuso, sem ambiguidade, e qualquer um pode faltar. Um `+` no fuso precisa ir como `%2B` na URL (limitação do formato de query string, documentada).

**Limites:** `size` de 1 a 100 (padrão 20), para um cliente não pedir a tabela inteira. `page`, `size` e período são validados no serviço, que devolve todos os problemas de uma vez em `detalhes`, em vez de parar no primeiro.

**Carteira inexistente é `404`**, não lista vazia, que esconderia o erro do cliente. Carteira existente sem operações é `200` com lista vazia.

**O extrato só mostra o que foi aplicado.** Uma repetição idempotente não cria linha e um débito recusado não grava nada; verificado contra o Oracle.

**`ExtratoService` e `ExtratoController` separados** de `LancamentoService` e `LancamentoController`: leitura e escrita têm necessidades diferentes (o extrato é `@Transactional(readOnly = true)`), e assim o registro de operação não foi alterado.

**O `WHERE` é montado só com os filtros informados**, em vez do padrão `(:from IS NULL OR data >= :from)`: ligar um `null` a um parâmetro de data costuma exigir o tipo SQL explícito com o driver da Oracle, e montar o texto só com o que foi informado evita esse problema (não testei a alternativa; escolhi o caminho que dispensa o risco).

## 28. Autenticação por API key em um filtro próprio, falhando fechado

**Decisão:** um filtro (`ApiKeyFilter`) exige o header `X-API-Key` em todas as rotas, menos `GET /actuator/health`. Sem o header, ou com chave errada, é `401` no formato padrão de erro (`NAO_AUTORIZADO`).

**Por que um filtro e não o Spring Security:** o enunciado pede só "autenticação por API key" e exclui JWT e OAuth. O Spring Security traria uma cadeia de filtros inteira, sessões e CSRF para configurar e desligar. Um filtro pequeno (cerca de 30 linhas de lógica) faz o necessário e eu consigo explicar cada linha. O custo é manter eu mesmo o que o framework daria pronto; com múltiplos clientes, papéis ou rotação eu migraria para ele.

**Falha fechada:** a chave vem de `WALLET_API_KEY` (o mesmo `.env` das senhas). Se faltar, a aplicação não sobe (o Spring não resolve o placeholder); se tiver menos de 16 caracteres, a configuração recusa. Nunca existe "sem chave, API aberta por esquecimento". Verificado: com uma chave curta a aplicação se recusou a subir.

**Comparação em tempo constante:** comparo os hashes SHA-256 da chave recebida e da esperada com `MessageDigest.isEqual`. Um `equals` de texto termina no primeiro caractere diferente, e o tempo de resposta poderia revelar a chave aos poucos; comparar hashes também elimina o vazamento do tamanho.

**Mesma resposta para "faltou" e "estava errada",** e rota inexistente sem chave também é `401`, para não revelar quais rotas existem. A chave nunca é escrita em log (verificado: zero ocorrências no log da aplicação).

**O `401` sai no mesmo formato dos demais erros:** o filtro roda antes do Spring MVC e não passa pelo `@RestControllerAdvice` sozinho, então ele entrega a exceção ao `HandlerExceptionResolver`, que a leva ao `GlobalExceptionHandler`.

**Só a saúde é pública,** e só o caminho exato (`/actuator/health` e abaixo dele): `/actuator/healthz` e `/wallets/actuator/health` continuam exigindo chave (testado). O `/actuator/info` também exige.

**Limitações e o que eu faria com mais tempo:** uma única chave compartilhada (sem identificar o cliente), trocada por reinício; a API fala HTTP puro, então em produção a chave só é segura atrás de TLS (HTTPS terminado em um proxy ou balanceador). Com mais tempo: várias chaves por cliente guardadas com hash, rotação sem parar o serviço, limite de taxa por chave e auditoria de qual chave fez cada operação.

## 29. Documentação OpenAPI gerada a partir do código (springdoc)

**Decisão:** springdoc-openapi 3.2.1 gera o contrato (OpenAPI 3.1) em `/v3/api-docs` e serve o Swagger UI em `/swagger-ui.html`. O enunciado aceita documentação "gerada ou versionada"; escolhi **gerada**, porque um contrato escrito à mão (ou uma cópia commitada) fica desatualizado assim que uma rota muda. Verificado que essa versão funciona com o Spring Boot 4.2.0 (a biblioteca não é gerenciada pelo Boot, então a versão fica fixada no `pom.xml`).

**Por que o springdoc:** é a biblioteca mais usada para isso no Spring. Equivale ao `@nestjs/swagger`.

**A API key aparece no contrato** como esquema `apiKey` no header `X-API-Key`, com exigência global; o botão **Authorize** do Swagger UI passa a mandar o header em todas as chamadas, então dá para testar as rotas protegidas pelo navegador.

**A documentação é pública, as rotas não:** `/v3/api-docs` e `/swagger-ui` entram na lista de caminhos sem chave, ao lado da saúde (só o caminho exato e o que vem abaixo dele; `/swagger-uix` continua `401`, testado). O contrato não expõe dado nenhum, e o avaliador precisa conseguir lê-lo sem credencial. Em produção eu poderia desligar o Swagger UI por perfil.

**Um bug que a verificação real pegou:** o springdoc anexa o tipo de retorno do método a **toda** resposta anotada, inclusive às de erro, e documentava um `404` devolvendo uma `CarteiraResponse` e um `422` devolvendo um `LancamentoResponse`. Corrigi com um `OpenApiCustomizer` (`RespostasDeErroCustomizer`) que faz duas coisas para todas as operações: acrescenta o `401` (o filtro vale para todas as rotas) e faz todo `4xx` e `5xx` apontar para o formato padrão de erro (`ErroResponse`). Em vez de repetir uma anotação de erro em cada rota, a regra fica em um lugar só, e tem teste, incluindo o caso do corpo anexado por engano.

**Anotações nos controllers e DTOs** (`@Operation`, `@ApiResponse`, `@Parameter`, `@Schema`): resumo e regras de idempotência de cada rota, todos os códigos de resposta, o header `Idempotent-Replayed`, e exemplos e descrições dos campos.

**Limitações:**
- Os exemplos numéricos do OpenAPI são números JSON, então `70.00` aparece como `70.0` no Swagger; a API real devolve `70.00`.
- Não há teste que suba o springdoc inteiro (isso exigiria o contexto completo, com banco). Os testes cobrem a configuração e o *customizer* como unidades; o contrato gerado foi verificado contra a aplicação real. Com o Testcontainers, um teste de integração poderia baixar `/v3/api-docs` e conferir as rotas.
- Não commitei uma cópia do `openapi.json`: ela envelheceria sem um teste que detecte divergência.

## 30. Testes de integração contra um Oracle real (Testcontainers), separados dos unitários

**Decisão:** as classes `*IT` sobem a aplicação inteira contra um Oracle descartável criado pelo Testcontainers, e rodam no *failsafe* (fase `verify`), não no *surefire*. Os testes unitários (`*Test`) continuam em `./mvnw test`, rápidos e sem Docker.

**Por quê Oracle real e não um banco em memória:** o enunciado proíbe banco em memória, e o que está em jogo (PL/SQL, triggers, `FOR UPDATE`, privilégios entre schemas) não existe em outro banco. Um teste contra H2 não provaria nada disso.

**Fidelidade ao ambiente real:**
- **Mesma imagem** do `docker-compose.yml`, com tag fixa (`gvenzl/oracle-free:23.26.4-slim-faststart`), nunca `latest`.
- Os três usuários são criados pelo **mesmo script** `db/init/01_usuarios.sh`, copiado para o container, e não por um atalho do teste: se o script quebrar, o teste quebra. Foi esse tipo de falha que apareceu antes: um `CREATE SEQUENCE` que faltava para o `WALLET_AUDIT`, descoberto ao aplicar tudo do zero à mão; agora o teste refaz esse caminho a cada execução.
- As migrations rodam pelo `FlywayConfig` da aplicação, e a aplicação conecta como `WALLET_APP`, o usuário de privilégio mínimo.

**Container único para todas as classes** (padrão "singleton"): sobe uma vez e todas o reaproveitam; o Testcontainers o encerra ao fim. Os testes não dependem de estado limpo: cada um cria a sua própria carteira e só consulta o que é dela.

**Propriedades dinâmicas** (`@DynamicPropertySource`) fornecem as senhas e a chave, com a mesma precedência sobre qualquer `.env` da máquina: o teste não depende do `.env` nem do banco do compose rodando.

**O que `BancoIT` prova** (antes só verificado à mão com `sqlplus`, agora no CI): migrations aplicadas e sem objeto inválido; o `WALLET_APP` sem DML nem leitura das tabelas e sem acesso à auditoria; extrato imutável até para o dono; `CHECK` de saldo; e a trilha com saldo anterior, novo, operação e usuário, com a alteração por SQL direto aparecendo sem operação.

**Dois problemas reais que apareceram:**
- O prazo padrão de inicialização do Testcontainers (~1 min) é menor do que o Oracle precisa nesta máquina (~2 min); o log mostrava o banco subindo normalmente quando o teste desistiu. Subi o teto para 5 minutos, que também dá folga ao CI. É só um teto: o teste segue assim que o banco responde.
- O driver da Oracle recusa `commit()` com auto-commit ligado; as conexões diretas dos testes usam auto-commit desligado e confirmam explicitamente.

**Limitações:**
- Lento: a primeira execução baixa a imagem e o banco leva cerca de 2 minutos para ficar pronto. Por isso ficam em um job próprio do CI, em paralelo com os unitários.
- Exige Docker e memória (uns 4 GB): em máquina modesta é preciso parar o banco de desenvolvimento antes.
- Um único banco compartilhado por todas as classes `*IT`: elas não podem rodar em paralelo entre si.

## 31. Testes de integração pela API e de concorrência em Java, com "teste do teste"

**Contexto.** O item 30 provou o banco por SQL. Faltava provar a API de ponta a ponta (HTTP real, filtro de API key, JSON, erros) e, principalmente, que a garantia de concorrência se mantém quando quem chama são várias threads Java pela API, não só sessões `sqlplus`.

**Decisão.**
- `OperacoesIT` (6) e `OpenApiIT` (4) usam o servidor de verdade (`RANDOM_PORT`) e um cliente HTTP do próprio JDK (`ClienteHttp`), sem mocks.
- `ConcorrenciaIT` (5) dispara as chamadas todas ao mesmo tempo (duas travas `CountDownLatch`: todas as threads ficam prontas e só então largam juntas). Os cenários: 30 débitos de 10 sobre saldo 100 (exatamente 10 `201` e 20 `422`, saldo 0); 20 chamadas idênticas com a mesma chave (um só lançamento, 19 repetições); mesma chave com conteúdos diferentes (um vence, o resto `409`); créditos e débitos misturados; 5 carteiras em paralelo sem interferência.
- O resultado nunca é conferido só pelo que a API responde: `ConferenciaDoBanco` consulta o banco por JDBC (saldo, soma do extrato, saldo do último lançamento e cadeia da auditoria sem quebras).

**Teste do teste (mutação).** Um teste de concorrência que passa não prova nada se ele também passaria com o código errado. Removi temporariamente o `FOR UPDATE WAIT 5` da `PKG_WALLET` e rodei só o `ConcorrenciaIT`: **5 de 5 testes falharam** (17:16 a 17:19, 116 s). Restaurei o arquivo e conferi que ficou idêntico ao do git. Ou seja, o teste detecta justamente o defeito que o lock existe para impedir.

**Achado pelo `OpenApiIT`.** Sem `produces`, o springdoc documentava as respostas como `*/*` (qualquer tipo). Os controllers passaram a declarar `produces = application/json`, e há um teste que impede a volta do `*/*`.

**Limitações.** Tudo roda em uma única instância da aplicação, com pool de 10 conexões do Hikari; o lock é do banco, então várias instâncias seriam protegidas igualmente, mas isso não foi exercitado. Testes de concorrência têm um componente de temporização: a barreira aumenta muito a chance de colisão, mas não a garante.

## 32. Cobertura de testes com JaCoCo e um piso que só sobe

**Contexto.** Os testes passam, mas nada impedia um PR futuro de adicionar código sem teste algum.

**Decisão.** JaCoCo mede a cobertura dos testes unitários (surefire) e de integração (failsafe) em arquivos separados, que são juntados no `verify`. O build falha abaixo de **90% das linhas e 80% dos ramos**. No CI, um job próprio (`Cobertura`) baixa os dados dos dois jobs de teste, junta, gera o relatório HTML e confere o piso; ele é exigido pelo portão final.

**Números reais (medidos).** Linhas 97,6% (328 de 336), ramos 88,1% (74 de 84), com 122 unitários e 25 de integração. O que falta: 2 linhas no `ApiKeyFilter`, 3 no `GlobalExceptionHandler` e 3 espalhadas (um caminho de erro cada).

**Por que o piso é menor que o valor atual.** Uma folga evita quebrar o build por mudar uma linha de lugar, e ainda barra uma queda de verdade. A regra de equipe é o piso só subir.

**Por que juntar unitários e integração.** Os repositórios e o fluxo HTTP completo só são exercitados de verdade com o banco; medir só os unitários mostraria um número enganosamente baixo e empurraria para testes redundantes.

**Limitações.** Cobertura mede o que foi executado, não verificado: um teste sem `assert` dá 100%. Por isso a garantia de verdade vem do teste do teste (item 31), e o JaCoCo é só um piso contra regressão. A classe `main` fica fora da medição, e o PL/SQL não é medido pelo JaCoCo (tem `db/tests` e o `BancoIT`).

## 33. Coleção do Postman versionada, sem a chave, e testada de verdade

**Contexto.** Quem avalia (e eu mesmo, na entrevista) precisa chamar a API e simular operações sem escrever `curl`. O Swagger UI já cobre a leitura; faltava um roteiro executável das regras de negócio.

**Decisão.** Uma coleção Postman v2.1 e um ambiente local em `docs/postman/`. A coleção é um **roteiro**: cria a carteira, guarda o `id` em variável, e cada requisição traz asserções (`pm.test`) para o status e para os pontos que importam (saldo após cada operação, `Idempotent-Replayed`, mesmo lançamento na repetição). As chaves de idempotência são geradas por requisição (`{{$guid}}`) e guardadas, para a repetição reaproveitar a mesma.

**A API key não está nos arquivos.** O ambiente traz `apiKey` vazia e do tipo `secret`; quem usa preenche com o valor do `.env`. Conferi que o valor real não aparece em nenhum dos dois arquivos.

**Verificação.** Não confiei só em escrever o JSON: rodei a coleção inteira com o **newman** contra a aplicação real e o Oracle real. Resultado: 22 requisições, 23 asserções, 0 falhas. A primeira execução achou dois erros do **meu** roteiro (não da API): `headers.get` devolve `undefined` (não `null`) quando o header não existe, e eu gravava o id do lançamento depois de uma asserção que podia falhar.

**Limitações.** O Postman roda uma requisição por vez: ele mostra cada regra, mas não a disputa entre chamadas simultâneas, que é coberta pelo `ConcorrenciaIT`. A coleção depende de ordem (a pasta 2 usa a carteira da pasta 1) e não é rodada no CI.

## 34. Scripts de dia a dia em PowerShell (o "npm run" do projeto)

**Contexto.** O Maven não tem um bloco `scripts` como o `package.json`. Subir o ambiente exigia lembrar vários passos e, se a aplicação subisse antes de o Oracle estar pronto, o erro de conexão era pouco claro.

**Decisão.** Cinco atalhos em `scripts/`: `start`, `stop`, `test`, `verify` e `postman`, todos em PowerShell (`.ps1`). Cheguei a escrever e testar também a versão em Bash, mas removi: manter duas cópias do mesmo comportamento dobra a manutenção, e o ambiente do projeto é Windows. Preferi scripts a um Makefile porque não há `make` no Windows e eles não exigem instalar nada.

**Duplo clique.** No Windows, clicar duas vezes num `.ps1` abre o arquivo no editor em vez de executá-lo. Por isso `start.cmd` e `stop.cmd` são lançadores de duas linhas que chamam o PowerShell com `-ExecutionPolicy Bypass` só para aquele script (e `pause` para a janela não fechar antes de se ler um erro). O README documenta as duas maneiras (script, por duplo clique ou terminal, e passo a passo manual).

**Cuidados embutidos.** O `start` confere `.env`, tamanho da `WALLET_API_KEY` (sem imprimi-la), Docker e porta livre, e espera o banco ficar `healthy`. O `stop` só encerra o processo da porta 8080 se a linha de comando for a da nossa aplicação. O `verify` para o banco de desenvolvimento (o Docker não comporta dois Oracles) e o religa no fim mesmo se o build falhar (`try/finally`).

**Achado durante o teste dos scripts.** O banco **não tem volume nomeado**: os dados vivem na camada do container. O `docker compose up -d` recriou o container quando mudei de Git Bash (`c:\dev\...`) para PowerShell (`C:\dev\...`), porque a grafia do caminho entrou no cálculo de mudança de configuração. Recriar apaga as carteiras. Os scripts passam a usar `up -d --no-recreate`. Deixei o `docker-compose.yml` como está (mudar a infraestrutura é decisão à parte); um volume nomeado resolveria de vez.

**Armadilhas do PowerShell 5.1.** (1) Arquivos `.ps1` sem BOM com acento são lidos na codepage errada: os `.ps1` ficam só em ASCII. (2) O `docker` escreve avisos no stderr e, com `$ErrorActionPreference = 'Stop'`, isso derrubava o script: o `start.ps1` não usa o modo `Stop` e chama o docker por `cmd /c`.

**Verificação.** Executados de verdade: subir, recusar uma segunda subida, rodar a coleção do Postman (22 requisições, 23 asserções, 0 falhas), encerrar, e `verify` completo (122 unitários, 25 de integração e o piso de cobertura).

**Limitações.** Só Windows (consultam portas e processos com cmdlets do Windows). Não rodam no CI. O `start` espera no máximo 5 minutos pelo banco.

## 35. Identificador numérico no teste; UUID em produção

**Contexto.** O `id` da carteira que a API devolve é um número (`"id": 21`). Faz sentido perguntar se não deveria ser um UUID.

**Decisão.** Mantive `NUMBER` gerado por `SEQUENCE`, **apenas para fins deste teste**. **Em produção, o identificador exposto pela API seria um UUID.**

**Por que número aqui.** (1) O extrato ordena por data e, no empate, pelo `id` crescente (`ORDER BY criado_em DESC, id DESC`); com UUID aleatório o desempate viraria aleatório e a paginação deixaria de ser estável. (2) O desafio pede concorrência e idempotência resolvidas no banco, e o desenho com sequences (`CACHE 20`), `FOR UPDATE` e triggers usa o número. (3) É pequeno e rápido de indexar. (4) Não há vazamento entre usuários: a API tem uma chave única e nenhuma carteira pertence a um usuário específico.

**Por que UUID em produção.** Ids sequenciais são adivinháveis (`/wallets/21` convida a tentar o `22`), o que vira falha de autorização assim que houver mais de um usuário ou cliente, e revelam o volume de carteiras. Um UUID também pode ser gerado fora do banco, útil em sistemas distribuídos.

**Como migrar sem perder a ordem do extrato.** Manter o número internamente (chave primária, `FOR UPDATE`, ordenação) e acrescentar uma coluna `public_id` com UUID, única e indexada, usada em todas as rotas e respostas; ou adotar UUID v7, que embute o tempo e preserva a ordem. Custo: migrations, `PKG_WALLET`, triggers de auditoria, DTOs, rotas, os testes e a coleção do Postman.

## 36. Conferência contra o enunciado

Revisão final, ponto a ponto, do enunciado contra o repositório.

| Requisito do enunciado | Situação | Onde |
|---|---|---|
| Carteira: saldo BRL com 2 casas, inicial 0.00, id gerado, titular opcional | feito | `owner/V1`, `PKG_WALLET.CRIAR_CARTEIRA` |
| Crédito, débito, valor > 0, débito sem saldo rejeitado, extrato, idempotência, conflito de chave | feito | `PKG_WALLET.REGISTRAR_OPERACAO`, itens 4 a 6 |
| Extrato imutável (até por SQL direto) e trilha de auditoria alimentada pelo banco, sem acesso da aplicação | feito | `owner/V3` (triggers), `audit/V1`/`V2`, `BancoIT` |
| Concorrência: saldo nunca negativo, nada em dobro, saldo final consistente | feito e provado | lock `FOR UPDATE`, `ConcorrenciaIT` (e mutação, item 31) |
| 4.1 Três schemas, menor privilégio, grants versionados | feito | `db/init/01_usuarios.sh`, `owner/V6` |
| 4.2 Package PL/SQL, erros `-20000..-20999` documentados e traduzidos | feito | `PKG_WALLET`, README (tabela de erros) |
| 4.3 Sequences explícitas e seu comportamento documentado | feito | item 7 e README (`CACHE 20` e lacunas) |
| 4.4 Triggers de imutabilidade e de auditoria | feito | `owner/V3` |
| 5 Os 4 endpoints, payload de erro padronizado, status HTTP defendidos | feito | itens 17, 21, 26, 27, Swagger |
| 6 Docker: tag fixa, healthcheck, schemas por script, senhas por variável de ambiente | feito | `docker-compose.yml`, `.env.example` |
| 7.1 Testes unitários (tradução de erros e saldo insuficiente) | feito | 122 testes |
| 7.1 README com passo a passo, modelagem, objetos, decisões, como rodar e testar | feito | README |
| 7.2 OpenAPI/Swagger, API key, migrations versionadas, mapeamento ORA-20xxx | feito | itens 10, 12, 28, 29 |
| 7.2 Concorrência e idempotência dentro do banco, justificadas no README | feito | README (seção de estratégia) |
| 7.2 Testes de integração com Oracle real e ao menos um concorrente | feito | 25 testes (`BancoIT`, `OperacoesIT`, `ConcorrenciaIT`, `OpenApiIT`) |
| 7.3 Teste de carga, transferência, limite diário, conciliação, Dockerfile | **não feito** | escopo: ver "O que eu faria com mais tempo" |
| 7.3 Observabilidade mínima | parcial | `health` e `info` do Actuator; sem métricas e logs estruturados |
| 8 Histórico de commits com evolução, repositório em nome do candidato | feito | commits e PRs pequenos, todos no e-mail do candidato |
| 8 DECISIONS com o que escolheu, por quê e o que faria diferente | feito | este arquivo |
| 9 Premissas documentadas | feito | README (seção "Premissas assumidas") |

**Pendências fora do código:** o e-mail para avaliacao.tech@datum.inf.br (assunto "Conclusão Avaliação – [nome completo]", com o link do repositório) e a combinação do prazo com a Datum.

## Limitações conhecidas

- A trigger de imutabilidade não pega `TRUNCATE` (DDL), e o dono do schema pode desabilitá-la. Em produção: auditoria nativa do Oracle e controle de quem tem privilégio de DDL.
- O OWNER pode chamar `PKG_AUDIT` com valores falsos (ver item 3).
- Um débito recusado não deixa rastro (ver item 4).

## O que eu faria com mais tempo

- Transferência entre carteiras em uma procedure (débito e crédito atômicos, com ordem fixa de lock por ID para evitar deadlock).
- Limite diário de débito resolvido no banco.
- Job de conciliação com `DBMS_SCHEDULER` (soma do extrato igual ao saldo; divergências na auditoria). O campo `SALDO_APOS` já ajuda a conferir.
- Auditoria nativa do Oracle (`AUDIT`) como camada extra além das triggers.
- Identificador público da carteira como UUID (item 35): hoje é um número, só para fins do teste.
