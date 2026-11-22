# Wallet API

Serviço de carteira digital (crédito e débito, extrato imutável, trilha de auditoria) em **Java + Spring Boot** sobre **Oracle Database Free**. As regras de consistência (saldo nunca negativo, nenhuma operação aplicada em dobro, mesmo sob concorrência) são resolvidas **dentro do banco**, em PL/SQL, triggers e sequences. A aplicação Java é uma camada de API sobre elas.

> **Estado atual:** camada de banco pronta e testada (schemas, tabelas, auditoria, `PKG_WALLET`, grants), projeto Spring Boot com o Flyway aplicando as migrations no startup, e os endpoints de **criar** e **consultar** carteira, de **registrar operação** (crédito e débito, com idempotência) e o **extrato** (paginado, com filtro por período), com tradução padronizada dos erros `ORA-20xxx` para HTTP, autenticação por **API key** (`X-API-Key`) e a documentação **OpenAPI/Swagger** gerada a partir do código. Os testes de integração com Testcontainers estão em construção e entram na próxima etapa. Este README é atualizado a cada etapa.

**Stack:** Java 21, Spring Boot 4.2.0, Maven (wrapper incluído), Flyway, driver JDBC da Oracle, Oracle Database Free 23ai (Docker).

As decisões técnicas e os motivos estão em [DECISIONS.md](DECISIONS.md).

## Pré-requisitos

- Docker (Docker Desktop com WSL2 no Windows, ou Docker Engine no Linux/macOS) com **pelo menos 4 GB de memória** disponíveis. Com menos de 2 GB o Oracle não inicializa.
- Git Bash (Windows) ou qualquer shell POSIX.
- Java 17+ (o projeto foi desenvolvido com Java 21). O Maven não precisa estar instalado: o wrapper `./mvnw` baixa a versão certa.

## Subir o banco

```bash
cp .env.example .env        # troque os valores: senhas só com letras e números; WALLET_API_KEY com 16+ caracteres
docker compose up -d
docker compose logs -f      # espere aparecer "DATABASE IS READY TO USE!" (Ctrl+C sai dos logs)
docker compose ps           # STATUS deve ficar "healthy"
```

O script [`db/init/01_usuarios.sh`](db/init/01_usuarios.sh) roda **só na primeira inicialização** (diretório de dados vazio) e cria os três usuários no PDB `FREEPDB1`. Para recriar do zero: `docker compose down -v && docker compose up -d`.

As senhas vêm do `.env`, que **não** vai para o Git. A imagem usa tag fixa (`gvenzl/oracle-free:23.26.4-slim-faststart`).

## Schemas e privilégios

| Usuário | Quem usa | O que pode |
|---|---|---|
| `WALLET_OWNER` | migrations (Flyway) | cria e é dono de tabelas, sequences, packages e triggers. A aplicação nunca conecta com ele |
| `WALLET_APP` | aplicação Spring | conectar, `EXECUTE` no `PKG_WALLET` e `SELECT` nas views `V_CARTEIRA` e `V_EXTRATO`. **Sem DML em tabelas**, sem acesso à auditoria |
| `WALLET_AUDIT` | ninguém no dia a dia | dono da trilha de auditoria. O `WALLET_OWNER` só tem `EXECUTE` na procedure que grava nela |

Conexão para inspeção (DBeaver, SQL Developer): host `localhost`, porta `1521`, **Service Name** `FREEPDB1`, um usuário por conexão.

## Migrations

Ficam em `src/main/resources/db/migration/`, em **dois conjuntos**, cada um executado pelo seu usuário. A ordem importa: `audit` primeiro, porque a trigger do OWNER chama a procedure do schema de auditoria.

| Arquivo | Conteúdo |
|---|---|
| `audit/V1__tabela_e_sequence_auditoria.sql` | `SALDO_AUDITORIA` e sua sequence |
| `audit/V2__pkg_audit.sql` | `PKG_AUDIT.REGISTRAR_SALDO` e `GRANT EXECUTE` ao OWNER |
| `owner/V1__tabelas_e_sequences.sql` | `CARTEIRA`, `LANCAMENTO`, sequences, índice do extrato |
| `owner/V2__pkg_wallet_ctx.sql` | contexto da operação em andamento (para a auditoria) |
| `owner/V3__triggers.sql` | imutabilidade do extrato e auditoria de saldo |
| `owner/V4__pkg_wallet.sql` | `PKG_WALLET`: criar carteira e registrar operação |
| `owner/V5__views_de_leitura.sql` | `V_CARTEIRA` e `V_EXTRATO` |
| `owner/V6__grants_wallet_app.sql` | privilégios mínimos do `WALLET_APP` |

As migrations são aplicadas **automaticamente quando a aplicação sobe**, pela classe [`FlywayConfig`](src/main/java/br/com/renanfranca/walletapi/config/FlywayConfig.java): primeiro o conjunto `audit` (conectada como `WALLET_AUDIT`), depois o `owner` (como `WALLET_OWNER`). Cada conjunto tem a sua tabela `flyway_schema_history` no próprio schema. A aplicação, ao servir requisições, usa só o `WALLET_APP`.

## Estrutura do projeto e dependências

Pacote raiz `br.com.renanfranca.walletapi` (group `br.com.renanfranca`, artifact `wallet-api`), Java 21, Maven, empacotado como jar com Tomcat embutido.

```
src/main/java/br/com/renanfranca/walletapi/
  WalletApiApplication.java        ponto de entrada
  config/FlywayConfig.java         executa as migrations no startup
  controller/                      rotas HTTP (só traduz HTTP; sem regra de negócio)
  service/                         casos de uso e controle da transação (@Transactional)
  repository/                      acesso ao banco: escreve pelo PKG_WALLET, lê pelas views
  model/                           objetos de domínio (records imutáveis)
  dto/                             corpos de requisição e resposta
  exception/                       catálogo de erros, payload padrão e tradução ORA-20xxx -> HTTP
src/main/resources/
  application.properties           conexão (WALLET_APP), senhas do .env, Actuator
  db/migration/{audit,owner}/      migrations Flyway
db/                                scripts de init do container e testes de banco
```

| Dependência | Para que | Por que |
|---|---|---|
| Spring Web | endpoints REST | os 4 endpoints do enunciado |
| JDBC API | `JdbcTemplate`/`SimpleJdbcCall` | toda escrita passa pelo package PL/SQL; leitura por views |
| Validation | validação do corpo das requisições | a regra definitiva continua no banco |
| Flyway + módulo Oracle | migrations versionadas | exigido; o suporte a Oracle é um módulo separado desde o Flyway 10 |
| Oracle Driver (ojdbc17) | conexão com o Oracle | escopo runtime |
| Actuator | `health` e `info` | observabilidade mínima |
| Testcontainers (oracle-free) | Oracle real nos testes | testes de integração, incluindo concorrência |

**Não incluídos de propósito:** JPA/Hibernate (a escrita é via package e a leitura são views simples), Lombok (menos código gerado escondido) e Spring Security (a autenticação é por API key, sem JWT/OAuth). Os motivos estão em [DECISIONS.md](DECISIONS.md).

## Rodar a aplicação

Com o banco `healthy` e o `.env` criado na raiz (a aplicação lê as senhas e a `WALLET_API_KEY` dele; **sem uma `WALLET_API_KEY` de 16+ caracteres a aplicação não sobe**):

```bash
./mvnw spring-boot:run
# ou, gerando o jar:
./mvnw -DskipTests package
java -jar target/wallet-api-0.0.1-SNAPSHOT.jar
```

Execute a partir da **raiz do projeto**, para a aplicação encontrar o `.env` (em container ou CI, as mesmas variáveis podem vir do ambiente). Na primeira subida o Flyway cria tudo; nas seguintes ele só valida (`Schema ... is up to date`).

```bash
curl http://localhost:8080/actuator/health    # {"status":"UP"}  (a única rota pública)
```

## Modelo de dados

- **`CARTEIRA`**: `ID` (sequence), `TITULAR` (opcional), `SALDO NUMBER(15,2)` com `CHECK (SALDO >= 0)`, `CRIADA_EM`.
- **`LANCAMENTO`** (extrato): `ID`, `CARTEIRA_ID`, `TIPO` (`CREDITO` ou `DEBITO`), `VALOR > 0`, `SALDO_APOS`, `CHAVE_IDEMPOTENCIA`, `CRIADO_EM`. `UNIQUE (CARTEIRA_ID, CHAVE_IDEMPOTENCIA)`.
- **`SALDO_AUDITORIA`** (schema de auditoria): saldo anterior, saldo novo, operação que causou a mudança, usuário do banco e momento.

Triggers: o extrato é imutável (`UPDATE` e `DELETE` retornam `ORA-20005`, inclusive por SQL direto) e toda mudança de saldo grava a trilha na mesma transação.

## Documentação interativa (OpenAPI e Swagger UI)

Com a aplicação no ar:

| O que | Endereço |
|---|---|
| **Swagger UI**: ler a API e testar as rotas no navegador | <http://localhost:8080/swagger-ui.html> |
| **Contrato OpenAPI 3.1** em JSON (para gerar clientes, importar no Postman etc.) | <http://localhost:8080/v3/api-docs> |

A documentação é **pública** (o contrato não expõe dado nenhum); **as rotas continuam exigindo `X-API-Key`**. Para chamá-las pelo Swagger UI, clique em **Authorize**, cole o valor da `WALLET_API_KEY` e confirme: a partir daí o Swagger UI manda o header em todas as chamadas.

O contrato é **gerado a partir do código** (springdoc-openapi 3.2.1), então não fica desatualizado em relação às rotas: cada operação traz resumo, parâmetros, regras de idempotência, exemplos e **todos os códigos de resposta**, e as respostas de erro (`400`, `401`, `404`, `409`, `422`, `503`) apontam para o mesmo formato padrão de erro. Para guardar uma cópia: `curl http://localhost:8080/v3/api-docs > openapi.json`.

## API

### Autenticação (API key)

**Todas as rotas exigem o header `X-API-Key`**, com o valor de `WALLET_API_KEY` do `.env`. A única exceção é `GET /actuator/health` (saúde), que orquestradores e monitoramento precisam chamar sem credencial.

```bash
export WALLET_API_KEY=...   # o mesmo valor do .env
curl -H "X-API-Key: $WALLET_API_KEY" http://localhost:8080/wallets/21
```

Sem o header, ou com a chave errada ou vazia: `401` com o formato padrão de erro, e a mesma mensagem nos dois casos (para não ajudar quem tenta adivinhar):

```json
{"codigo":"NAO_AUTORIZADO","mensagem":"API key ausente ou inválida.","timestamp":"...","path":"/wallets/21","detalhes":[]}
```

A saúde (`/actuator/health`) e a documentação (`/v3/api-docs` e `/swagger-ui`) são as únicas rotas públicas. Uma rota inexistente sem chave também devolve `401`, para não revelar quais rotas existem. A chave é comparada em tempo constante e nunca é escrita em log. Os exemplos abaixo já incluem o header (defina `WALLET_API_KEY` no seu shell, como acima).

### Rotas

| Método e rota | O que faz | Sucesso | Erros |
|---|---|---|---|
| `POST /wallets` | cria uma carteira com saldo `0.00`. Corpo opcional: `{"titular": "Maria"}` (até 120 caracteres) | `201` + header `Location` + carteira | `400` |
| `GET /wallets/{id}` | consulta a carteira e o saldo | `200` + carteira | `404`, `400` (id não numérico) |
| `GET /wallets/{id}/transactions` | extrato da carteira, do mais recente para o mais antigo. Parâmetros opcionais: `from`, `to`, `page`, `size` | `200` + página | `400`, `404` |
| `POST /wallets/{id}/transactions` | registra um crédito ou débito. Corpo: `{"tipo": "CREDITO" ou "DEBITO", "valor": 100.00}`. **Exige o header `Idempotency-Key`** | `201` + lançamento (inclusive na repetição) | `400`, `404`, `409`, `422`, `503` |

Exemplo:

```bash
curl -X POST http://localhost:8080/wallets -H "X-API-Key: $WALLET_API_KEY" -H 'Content-Type: application/json' -d '{"titular":"Maria"}'
# 201  {"id":21,"titular":"Maria","saldo":0.00,"criadaEm":"2026-11-21T11:51:33.635316Z"}

curl -H "X-API-Key: $WALLET_API_KEY" http://localhost:8080/wallets/21
curl -H "X-API-Key: $WALLET_API_KEY" http://localhost:8080/wallets/999999
# 404  {"codigo":"CARTEIRA_NAO_ENCONTRADA","mensagem":"Carteira não encontrada.","timestamp":"...","path":"/wallets/999999","detalhes":[]}
```

**Registrar operação e idempotência:**

```bash
# crédito de 100.00
curl -X POST http://localhost:8080/wallets/21/transactions \
  -H "X-API-Key: $WALLET_API_KEY" -H 'Content-Type: application/json' -H 'Idempotency-Key: pedido-123' \
  -d '{"tipo":"CREDITO","valor":100.00}'
# 201  {"id":21,"carteiraId":21,"tipo":"CREDITO","valor":100.00,"saldoApos":100.00,"chaveIdempotencia":"pedido-123",...}
```

- **Mesma chave e mesmo conteúdo** (retry, timeout): o saldo é afetado **uma única vez** e a resposta é a da primeira chamada (`201`, mesmo corpo), marcada com o header `Idempotent-Replayed: true`.
- **Mesma chave com conteúdo diferente:** `409 CHAVE_IDEMPOTENCIA_CONFLITO`.
- **Débito maior que o saldo:** `422 SALDO_INSUFICIENTE`, sem alterar o saldo.
- **Sem `Idempotency-Key`, valor `<= 0`, mais de 2 casas decimais ou tipo inválido:** `400 DADOS_INVALIDOS`, com o motivo em `detalhes`.
- Sob concorrência na mesma carteira, o banco enfileira as operações (lock da carteira): o saldo nunca fica negativo e nada é aplicado em dobro.

**Extrato:**

```bash
# 2 itens por página, a página 1 (a segunda), só novembro de 2026
curl -H "X-API-Key: $WALLET_API_KEY" 'http://localhost:8080/wallets/21/transactions?page=1&size=2&from=2026-11-01T00:00:00Z&to=2026-12-01T00:00:00Z'
# 200  {"itens":[{"id":22,...,"saldoApos":70.00,...}], "pagina":1, "tamanho":2, "totalItens":5, "totalPaginas":3}
```

| Parâmetro | Significado | Padrão |
|---|---|---|
| `page` | número da página, a partir de **0** | `0` |
| `size` | itens por página, de **1 a 100** | `20` |
| `from` | início do período, **inclusivo**, ISO-8601 com fuso (`2026-11-01T00:00:00Z`) | sem limite |
| `to` | fim do período, **exclusivo** (intervalo `[from, to)`) | sem limite |

Ordem: do mais recente para o mais antigo (data e id). Só entram os lançamentos **aplicados**: uma repetição idempotente e um débito recusado não criam linha no extrato. Carteira inexistente é `404` (não uma lista vazia); carteira sem operações é `200` com lista vazia. Parâmetros inválidos (`size` fora de 1 a 100, `page` negativo, `from` depois de `to`, data mal formada) são `400` com o motivo em `detalhes`. Atenção: um `+` no fuso (`+03:00`) precisa ir codificado como `%2B` na URL.

**Formato padrão de erro** (toda a API, inclusive rota inexistente e método não permitido):

| Campo | Conteúdo |
|---|---|
| `codigo` | identificador estável para o cliente tratar por código (ex.: `SALDO_INSUFICIENTE`) |
| `mensagem` | texto legível |
| `timestamp` | momento do erro |
| `path` | caminho da requisição |
| `detalhes` | lista com informações extras (campos inválidos, regra violada); vazia quando não há |

A mensagem do Oracle (nomes de objeto, stack) nunca vai para o cliente. Erros inesperados saem como `500 ERRO_INTERNO`, com o detalhe só no log.

## `PKG_WALLET` e códigos de erro

Toda escrita passa pelo package. As procedures **não fazem `COMMIT`**: quem chama controla a transação.

- `CRIAR_CARTEIRA(titular, OUT id, OUT saldo, OUT criada_em)`
- `REGISTRAR_OPERACAO(carteira, tipo, valor, chave, OUT lancamento_id, OUT saldo_apos, OUT criado_em, OUT repetida)`

| Código do banco | `codigo` na API | Significado | HTTP |
|---|---|---|---|
| `-20001` | `CARTEIRA_NAO_ENCONTRADA` | carteira não encontrada | 404 |
| `-20002` | `DADOS_INVALIDOS` | dados inválidos (tipo, valor, chave, titular) | 400 |
| `-20003` | `SALDO_INSUFICIENTE` | saldo insuficiente | 422 |
| `-20004` | `CHAVE_IDEMPOTENCIA_CONFLITO` | chave de idempotência reutilizada com payload diferente | 409 |
| `-20005` | `EXTRATO_IMUTAVEL` | extrato imutável | 409 |
| `-20006` | `CARTEIRA_OCUPADA` | carteira ocupada (lock não obtido em 5 s) | 503 |

**Por que cada status:** `400` a requisição é inválida em si; `404` o recurso não existe; `409` conflito com o estado atual; `422` a requisição está bem formada mas viola uma regra de negócio (saldo insuficiente); `503` falha transitória, o cliente pode tentar de novo. A tradução está em um único lugar: `exception/ErroNegocio` e `GlobalExceptionHandler`.

## Como testar

**Testes unitários (Java)**, sem banco e sem Docker: tradução de erros, catálogo, serviço e camada web.

```bash
./mvnw test
```

**Testes de integração (Oracle real)**: a aplicação inteira contra um Oracle descartável que o **Testcontainers** sobe em Docker, com a **mesma imagem do `docker-compose.yml`** (tag fixa) e os mesmos três usuários, criados pelo **mesmo** `db/init/01_usuarios.sh`. Exigem Docker e **não precisam do `.env` nem do banco do compose rodando**.

```bash
./mvnw verify                                                   # tudo: formato, lint, unitários e integração
./mvnw test-compile failsafe:integration-test failsafe:verify   # só os testes de integração (classes *IT)
```

A primeira execução baixa a imagem e o Oracle leva cerca de 2 minutos para ficar pronto. **Com pouca memória** (o Docker precisa de uns 4 GB), pare o banco de desenvolvimento antes (`docker compose stop`): dois Oracles ao mesmo tempo não cabem. Hoje são **25 testes**:

| Classe | Testes | O que prova |
|---|---|---|
| `BancoIT` | 10 | migrations aplicadas e sem objeto inválido, privilégio mínimo do `WALLET_APP`, extrato imutável até para o dono, `CHECK` de saldo e trilha de auditoria |
| `OperacoesIT` | 6 | a API de ponta a ponta por HTTP real: criar, creditar, repetir (idempotência), conflito, débito, saldo insuficiente, extrato paginado, validações, `404` e `401` |
| `ConcorrenciaIT` | 5 | **o coração do desafio**: várias operações na mesma carteira ao mesmo tempo (threads soltas juntas por uma barreira); saldo, extrato e auditoria conferidos **direto no banco** |
| `OpenApiIT` | 4 | o contrato OpenAPI que a aplicação realmente gera (público, `X-API-Key`, erros apontando para o formato padrão, tipo de mídia `application/json`) |

**O que o `ConcorrenciaIT` garante** (as três garantias do enunciado): (a) o saldo nunca fica negativo, (b) nenhuma operação é aplicada em dobro, (c) o saldo final é a soma consistente do que foi aplicado. Exemplos: 30 débitos simultâneos de 10 sobre saldo 100 aplicam **exatamente 10** e recusam 20; 20 chamadas iguais simultâneas (mesma chave) aplicam **uma vez só** e as outras 19 recebem o resultado original; créditos e débitos misturados em várias carteiras ao mesmo tempo mantêm as invariantes. Em todos, o teste confere direto no banco que o saldo é igual à soma do extrato e ao saldo do último lançamento, e que a trilha de auditoria tem uma linha por mudança de saldo, encadeada sem buraco.

**Camada de banco**, com o banco no ar e as migrations aplicadas (basta ter subido a aplicação uma vez):

```bash
# Git Bash no Windows: prefixe com MSYS_NO_PATHCONV=1

# 31 verificações de regra, executadas como WALLET_APP (o usuário real da aplicação)
docker compose exec -T oracle sh -c 'sqlplus -s WALLET_APP/"$WALLET_APP_PASSWORD"@//localhost:1521/FREEPDB1' < db/tests/regras_pkg_wallet.sql

# Concorrência: 3 cenários com 20 sessões simultâneas (N_SESSOES ajusta)
docker compose exec -T oracle bash -c "$(cat db/tests/concorrencia_pkg_wallet.sh)"
```

O teste de concorrência confere: saldo nunca negativo, nenhuma operação em dobro, saldo final igual à soma do extrato e trilha de auditoria encadeada sem buracos.

Para ver o teste falhar de verdade, remova temporariamente o `FOR UPDATE WAIT 5` do `PKG_WALLET`: o cenário 1 passa a aplicar 20 débitos em vez de 10 (ver [DECISIONS.md](DECISIONS.md)).

## Integração contínua (CI)

Todo PR para `develop` e `main` roda o workflow [`ci.yml`](.github/workflows/ci.yml) (GitHub Actions, Java 21), em jobs encadeados:

```
Qualidade  ->  Testes unitários  ----\
           ->  Testes de integração  ->  Build  ->  Build e testes (portão final)
```

| Job | O que roda |
|---|---|
| **Qualidade** | formatação (`spotless:check`), lint (`checkstyle:check`) e compilação estrita (`test-compile`: `-Xlint:all` com `failOnWarning`; em Java o typecheck é o compilador) |
| **Testes unitários** | os 122 testes (`./mvnw test`), sem banco e sem Docker |
| **Testes de integração** | os testes `*IT` contra um Oracle de verdade em Docker (Testcontainers), em paralelo com os unitários |
| **Build** | gera o jar executável (`./mvnw -DskipTests package`) |
| **Build e testes** | portão final: só passa se os quatro acima passarem |

O job `Build e testes` é o único **exigido pela proteção de branch**: sem ele verde, o merge é bloqueado. Para reproduzir tudo localmente: `./mvnw -B verify`.

Há ainda hooks de Git opcionais (`commit-msg` valida o padrão das mensagens; `pre-push` roda formato, lint e testes antes de enviar). Ativação: `git config core.hooksPath .githooks`.

Os padrões, as ferramentas, os testes que o CI exige e o motivo de cada escolha estão em [PADROES.md](PADROES.md).

## Provas de privilégio mínimo

Conectado como `WALLET_APP` (por exemplo no DBeaver): `INSERT`, `UPDATE`, `DELETE` ou `SELECT` direto nas tabelas, e qualquer acesso ao schema de auditoria, retornam `ORA-00942`. O teste `regras_pkg_wallet.sql` automatiza essa prova.

## Próximas etapas

Testes de integração com Testcontainers (incluindo o teste concorrente em Java), cobertura mínima (JaCoCo) e a revisão final dos documentos.
