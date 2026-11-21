# Wallet API

Serviço de carteira digital (crédito e débito, extrato imutável, trilha de auditoria) em **Java + Spring Boot** sobre **Oracle Database Free**. As regras de consistência (saldo nunca negativo, nenhuma operação aplicada em dobro, mesmo sob concorrência) são resolvidas **dentro do banco**, em PL/SQL, triggers e sequences. A aplicação Java é uma camada de API sobre elas.

> **Estado atual:** camada de banco pronta e testada (schemas, tabelas, auditoria, `PKG_WALLET`, grants) e projeto Spring Boot criado, com o Flyway aplicando as migrations no startup. Os endpoints, o Swagger, a API key e os testes em Java estão em construção e entram nas próximas etapas. Este README é atualizado a cada etapa.

**Stack:** Java 21, Spring Boot 4.2.0, Maven (wrapper incluído), Flyway, driver JDBC da Oracle, Oracle Database Free 23ai (Docker).

As decisões técnicas e os motivos estão em [DECISIONS.md](DECISIONS.md).

## Pré-requisitos

- Docker (Docker Desktop com WSL2 no Windows, ou Docker Engine no Linux/macOS) com **pelo menos 4 GB de memória** disponíveis. Com menos de 2 GB o Oracle não inicializa.
- Git Bash (Windows) ou qualquer shell POSIX.
- Java 17+ (o projeto foi desenvolvido com Java 21). O Maven não precisa estar instalado: o wrapper `./mvnw` baixa a versão certa.

## Subir o banco

```bash
cp .env.example .env        # troque os valores por senhas suas (só letras e números)
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

Com o banco `healthy` e o `.env` criado na raiz (a aplicação lê as senhas dele):

```bash
./mvnw spring-boot:run
# ou, gerando o jar:
./mvnw -DskipTests package
java -jar target/wallet-api-0.0.1-SNAPSHOT.jar
```

Execute a partir da **raiz do projeto**, para a aplicação encontrar o `.env` (em container ou CI, as mesmas variáveis podem vir do ambiente). Na primeira subida o Flyway cria tudo; nas seguintes ele só valida (`Schema ... is up to date`).

```bash
curl http://localhost:8080/actuator/health    # {"status":"UP"}
```

## Modelo de dados

- **`CARTEIRA`**: `ID` (sequence), `TITULAR` (opcional), `SALDO NUMBER(15,2)` com `CHECK (SALDO >= 0)`, `CRIADA_EM`.
- **`LANCAMENTO`** (extrato): `ID`, `CARTEIRA_ID`, `TIPO` (`CREDITO` ou `DEBITO`), `VALOR > 0`, `SALDO_APOS`, `CHAVE_IDEMPOTENCIA`, `CRIADO_EM`. `UNIQUE (CARTEIRA_ID, CHAVE_IDEMPOTENCIA)`.
- **`SALDO_AUDITORIA`** (schema de auditoria): saldo anterior, saldo novo, operação que causou a mudança, usuário do banco e momento.

Triggers: o extrato é imutável (`UPDATE` e `DELETE` retornam `ORA-20005`, inclusive por SQL direto) e toda mudança de saldo grava a trilha na mesma transação.

## `PKG_WALLET` e códigos de erro

Toda escrita passa pelo package. As procedures **não fazem `COMMIT`**: quem chama controla a transação.

- `CRIAR_CARTEIRA(titular, OUT id, OUT saldo, OUT criada_em)`
- `REGISTRAR_OPERACAO(carteira, tipo, valor, chave, OUT lancamento_id, OUT saldo_apos, OUT criado_em, OUT repetida)`

| Código | Significado | HTTP previsto |
|---|---|---|
| `-20001` | carteira não encontrada | 404 |
| `-20002` | dados inválidos (tipo, valor, chave, titular) | 400 |
| `-20003` | saldo insuficiente | 422 |
| `-20004` | chave de idempotência reutilizada com payload diferente | 409 |
| `-20005` | extrato imutável | 409 |
| `-20006` | carteira ocupada (lock não obtido em 5 s) | 503 |

## Como testar (camada de banco)

Com o banco no ar e as migrations aplicadas (basta ter subido a aplicação uma vez):

```bash
# Git Bash no Windows: prefixe com MSYS_NO_PATHCONV=1

# 31 verificações de regra, executadas como WALLET_APP (o usuário real da aplicação)
docker compose exec -T oracle sh -c 'sqlplus -s WALLET_APP/"$WALLET_APP_PASSWORD"@//localhost:1521/FREEPDB1' < db/tests/regras_pkg_wallet.sql

# Concorrência: 3 cenários com 20 sessões simultâneas (N_SESSOES ajusta)
docker compose exec -T oracle bash -c "$(cat db/tests/concorrencia_pkg_wallet.sh)"
```

O teste de concorrência confere: saldo nunca negativo, nenhuma operação em dobro, saldo final igual à soma do extrato e trilha de auditoria encadeada sem buracos.

Para ver o teste falhar de verdade, remova temporariamente o `FOR UPDATE WAIT 5` do `PKG_WALLET`: o cenário 1 passa a aplicar 20 débitos em vez de 10 (ver [DECISIONS.md](DECISIONS.md)).

## Provas de privilégio mínimo

Conectado como `WALLET_APP` (por exemplo no DBeaver): `INSERT`, `UPDATE`, `DELETE` ou `SELECT` direto nas tabelas, e qualquer acesso ao schema de auditoria, retornam `ORA-00942`. O teste `regras_pkg_wallet.sql` automatiza essa prova.

## Próximas etapas

Os quatro endpoints, tradução dos erros `ORA-20xxx` para HTTP, API key, OpenAPI/Swagger, testes unitários e testes de integração com Testcontainers (incluindo concorrência).
