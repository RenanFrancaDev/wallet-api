# Wallet API

Serviço de carteira digital (crédito e débito, extrato imutável, trilha de auditoria) em **Java + Spring Boot** sobre **Oracle Database Free**. As regras de consistência (saldo nunca negativo, nenhuma operação aplicada em dobro, mesmo sob concorrência) são resolvidas **dentro do banco**, em PL/SQL, triggers e sequences. A aplicação Java é uma camada de API sobre elas.

> **Estado atual:** entrega completa. Os 4 endpoints do enunciado (criar e consultar carteira, registrar operação com idempotência e extrato paginado com filtro por período), as regras de consistência dentro do Oracle (PL/SQL, triggers, sequences), autenticação por **API key**, documentação **OpenAPI/Swagger**, testes unitários e de integração (inclusive de **concorrência**, em Java, com Oracle real) e CI com piso de cobertura.

**Stack:** Java 21, Spring Boot 4.2.0, Maven (wrapper incluído), Flyway, driver JDBC da Oracle, Oracle Database Free 23ai (Docker).

As decisões técnicas e os motivos estão em [DECISIONS.md](DECISIONS.md).

## Tecnologias necessárias

| Tecnologia | Versão | Para quê | Obrigatória? |
|---|---|---|---|
| **Docker Desktop** (com WSL2 no Windows) | recente (testado com Docker 28.3); **4 GB de memória** para o Docker | roda o Oracle e os testes de integração. Com menos de 2 GB o Oracle não inicializa | sim |
| **Java (JDK)** | 17 ou superior (desenvolvido com o 21) | compila e roda a API | sim |
| **Maven** | não precisa instalar | o wrapper `./mvnw` (`mvnw.cmd` no PowerShell) baixa a versão certa | n/a |
| **Git** | qualquer | clonar o repositório | sim |
| **PowerShell** | 5.1 ou superior (já vem no Windows) | scripts `start`, `stop`, `verify` etc. Fora do Windows, use o passo a passo manual | recomendado |
| **Postman** | recente | testar e simular transações (coleção pronta) | opcional |
| **Node.js** (`npx`) | LTS | só para rodar a coleção do Postman no terminal (newman) | opcional |
| **DBeaver** ou SQL Developer | qualquer | inspecionar o banco (ver [Papéis no Oracle](#papéis-no-oracle-quem-faz-o-quê)) | opcional |

Já vêm no projeto, sem instalar nada: Spring Boot 4.2.0, Flyway, driver JDBC da Oracle, springdoc (Swagger) e Testcontainers. A imagem do banco é o **Oracle Database Free 23ai** (`gvenzl/oracle-free:23.26.4-slim-faststart`, tag fixa).

## Início rápido

1. **Configure o `.env`** (na raiz do projeto): `Copy-Item .env.example .env` no PowerShell ou `cp .env.example .env` no Git Bash, e troque os valores:
   - `ORACLE_PASSWORD`, `WALLET_OWNER_PASSWORD`, `WALLET_APP_PASSWORD`, `WALLET_AUDIT_PASSWORD`: senhas do banco, **só letras e números**.
   - **`WALLET_API_KEY`**: a **API key**. É a senha que todo cliente da API precisa mandar no header `X-API-Key`. **Quem escolhe é você**, com **16 ou mais caracteres**; sem ela a API **não sobe**. Para gerar uma, no PowerShell: `-join ((48..57)+(65..90)+(97..122) | Get-Random -Count 32 | ForEach-Object {[char]$_})`.
2. **Abra o Docker Desktop** e espere ele ficar "running".
3. **Suba tudo** (escolha uma das duas maneiras, detalhadas em [Subir o ambiente](#subir-o-ambiente-duas-maneiras)):
   - **Com o script:** duplo clique em `scripts\start.cmd`, ou `.\scripts\start.ps1` no PowerShell. Liga o banco, espera ficar `healthy` e sobe a API.
   - **Manual:** `docker compose up -d`, esperar `healthy` em `docker compose ps`, e `./mvnw spring-boot:run`.
4. **Confira:** `curl http://localhost:8080/actuator/health` deve responder `{"status":"UP"}`, e o Swagger UI abre em <http://localhost:8080/swagger-ui.html>. Na primeira vez o banco leva uns 2 minutos.
5. **Teste as rotas:** pelo Swagger UI (botão **Authorize**, cole a `WALLET_API_KEY`) ou pela [coleção do Postman](#testar-e-simular-transações-no-postman), que **também exige a API key**.

## Subir o ambiente: duas maneiras

Pré-requisito comum: o Docker Desktop **aberto**, e o arquivo `.env` criado na raiz (`cp .env.example .env` no Git Bash, ou `Copy-Item .env.example .env` no PowerShell; troque os valores, a `WALLET_API_KEY` precisa de 16+ caracteres).

### Maneira 1: pelo script (recomendada)

O script faz sozinho tudo o que a maneira 2 faz à mão: confere o `.env`, o Docker e a porta, liga o banco, **espera ele ficar `healthy`** e sobe a API. Há duas formas de chamá-lo:

- **Com 2 cliques:** no Explorador de Arquivos, abra a pasta `scripts` e dê **duplo clique em `start.cmd`**. Abre uma janela preta com o andamento; ela fica aberta enquanto a API roda (feche a janela, ou pressione Ctrl+C, para encerrar). Para encerrar sem fechar a janela, dê duplo clique em `stop.cmd`.
- **Pelo terminal** (PowerShell, na raiz do projeto): `.\scripts\start.ps1`. Se o Windows bloquear o script: `powershell -ExecutionPolicy Bypass -File .\scripts\start.ps1`.

Na **primeira vez** o banco leva uns 2 minutos para ficar pronto (a janela mostra `Esperando o Oracle ficar healthy`). Quando aparecer `Started WalletApiApplication`, a API está no ar: abra <http://localhost:8080/swagger-ui.html> ou rode `curl http://localhost:8080/actuator/health` (deve responder `{"status":"UP"}`).

### Maneira 2: manual, passo a passo

Útil para entender o que o script faz, ou fora do Windows. Na raiz do projeto:

1. **Ligar o banco:** `docker compose up -d`
2. **Esperar ficar pronto:** `docker compose ps` até a coluna de status mostrar `healthy` (ou `docker compose logs -f` até aparecer `DATABASE IS READY TO USE!`; Ctrl+C sai dos logs).
3. **Ligar a API:** `./mvnw spring-boot:run` (no PowerShell: `.\mvnw.cmd spring-boot:run`). Na primeira subida o Flyway cria os schemas, tabelas e o package.
4. **Conferir:** `curl http://localhost:8080/actuator/health` deve responder `{"status":"UP"}`.
5. **Encerrar:** Ctrl+C na janela da API. Para desligar o banco: `docker compose stop` (os dados ficam no container parado).

## Comandos do dia a dia (scripts)

Em [`scripts/`](scripts/) ficam os atalhos em **PowerShell**, o equivalente aos `npm run dev`, `npm test` etc. (Windows PowerShell 5.1 ou superior, o que já vem no Windows). `start` e `stop` também têm um lançador de **duplo clique** (`.cmd`):

| Para quê | Terminal (PowerShell) | 2 cliques |
|---|---|---|
| **Subir tudo para desenvolver**: confere o `.env`, liga o banco, espera ele ficar `healthy` e roda a aplicação | `.\scripts\start.ps1` | `scripts\start.cmd` |
| **Encerrar** a aplicação (só se a porta 8080 for mesmo dela); com `-Banco`, desliga o banco também | `.\scripts\stop.ps1 [-Banco]` | `scripts\stop.cmd` |
| **Testes unitários** (rápidos, sem Docker) | `.\scripts\test.ps1` | |
| **Tudo o que o CI roda** (formato, lint, unitários, integração e cobertura): para o banco de desenvolvimento antes e o religa no fim | `.\scripts\verify.ps1` | |
| **Rodar a coleção do Postman** no terminal (com a aplicação no ar) | `.\scripts\postman.ps1` | |

Por que `.cmd` e não clicar no `.ps1`: no Windows, o duplo clique num `.ps1` abre o arquivo no editor em vez de executá-lo. O `.cmd` chama o PowerShell já liberando a execução, só para aquele script.

Fora do Windows, use a maneira 2 (os scripts consultam processos e portas do Windows).

O `start` confere antes de subir: `.env` existe, `WALLET_API_KEY` tem 16+ caracteres (o valor nunca é impresso), Docker responde e a porta 8080 está livre. Ele usa `docker compose up -d --no-recreate`: o banco **não tem volume nomeado**, então os dados vivem dentro do container, e recriá-lo (o compose faz isso se só mudar a grafia do caminho, como `c:\` e `C:\`) apagaria as carteiras. Parar (`stop -Banco`) mantém os dados; `docker compose down` os apaga.

## Rodar a aplicação

Variações para rodar a aplicação:

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

## Testar e simular transações no Postman

Em [`docs/postman/`](docs/postman/) há uma coleção pronta com **22 requisições** (cada uma já com testes automáticos) e um ambiente local:

| Arquivo | O que é |
|---|---|
| `wallet-api.postman_collection.json` | a coleção: saúde, carteiras, operações com idempotência, validações, extrato, segurança e documentação |
| `wallet-api.local.postman_environment.json` | variáveis `baseUrl` (`http://localhost:8080`) e `apiKey` (vazia, do tipo secreto) |

> ### Atenção: sem a `apiKey`, nada funciona
> A API exige o header `X-API-Key` em todas as rotas (menos a de saúde). A coleção o envia sozinha, **mas o valor da chave não vem no arquivo do ambiente**, de propósito (chave não se versiona). **Você precisa preenchê-la uma vez**, senão toda requisição responde `401 NAO_AUTORIZADO` ("API key ausente ou inválida").
>
> 1. Abra o `.env` da raiz do projeto e copie o que vem depois de `WALLET_API_KEY=` (sem espaços e sem aspas). É a mesma chave com que a API subiu: se você mudar o `.env`, reinicie a API e atualize o Postman.
> 2. No Postman, abra o ambiente **Wallet API - local** (Environments, ou o ícone do olho ao lado do seletor) e cole o valor na coluna **Current value** da variável `apiKey`. Salve com Ctrl+S.
> 3. Confirme que o ambiente **Wallet API - local** está **selecionado** no canto superior direito. Com "No environment", a `apiKey` não existe.

**Ordem para funcionar** (siga na sequência):
1. **Suba a API** (`.\scripts\start.ps1` ou o passo a passo manual) e espere o `Started WalletApiApplication`.
2. **Importe a coleção:** no Postman, **Import** (ou **File > Import**) e escolha `docs/postman/wallet-api.postman_collection.json`.
3. **Importe o ambiente:** **Import** de novo, agora com `docs/postman/wallet-api.local.postman_environment.json`. (Também dá por **Environments > Import**.)
4. **Selecione o ambiente** **Wallet API - local** no seletor do canto superior direito. Com "No environment", a `apiKey` não existe.
5. **Preencha a `apiKey`** com o valor de `WALLET_API_KEY` do seu `.env` (quadro acima). **Sem isso todas as rotas respondem `401`.**
6. **Teste:** **0. Saúde** deve dar `200` (não precisa de chave) e **Criar carteira** deve dar `201`. `401` significa `apiKey` vazia ou errada.
7. **Rode as pastas em ordem** (ou botão direito na coleção, **Run collection**): a pasta 1 cria uma carteira e guarda o `id`; a pasta 2 usa essa carteira.

**Se der `ECONNREFUSED`:** a API não está no ar (ou ainda está subindo). Espere o `Started WalletApiApplication` no terminal.

**A simulação da pasta 2** (a que mostra a regra de negócio): crédito de 100,00 (saldo 100) → o mesmo crédito repetido com a mesma chave (responde `201` com o **mesmo** lançamento e `Idempotent-Replayed: true`, saldo continua 100) → a mesma chave com outro valor (`409`) → débito de 30,00 (saldo 70) → débito de 500,00 (`422`, nada muda) → consulta do saldo (70,00). Para repetir só o passo de idempotência com chaves novas, rode de novo o **Crédito de 100.00**: ele gera uma chave nova a cada execução.

**Sem Postman:** a mesma coleção roda no terminal com o newman: `npx newman run docs/postman/wallet-api.postman_collection.json -e docs/postman/wallet-api.local.postman_environment.json --env-var "apiKey=$WALLET_API_KEY"`.

> O Postman executa as requisições **uma depois da outra**. A concorrência de verdade (dezenas de chamadas simultâneas) é provada pelo `ConcorrenciaIT` (ver [Como testar](#como-testar)); no Postman dá para ver o resultado de cada regra, não a disputa.

## Documentação interativa (OpenAPI e Swagger UI)

Com a aplicação no ar:

| O que | Endereço |
|---|---|
| **Swagger UI**: ler a API e testar as rotas no navegador | <http://localhost:8080/swagger-ui.html> |
| **Contrato OpenAPI 3.1** em JSON (para gerar clientes, importar no Postman etc.) | <http://localhost:8080/v3/api-docs> |

A documentação é **pública** (o contrato não expõe dado nenhum); **as rotas continuam exigindo `X-API-Key`**. Para chamá-las pelo Swagger UI, clique em **Authorize**, cole o valor da `WALLET_API_KEY` e confirme: a partir daí o Swagger UI manda o header em todas as chamadas.

O contrato é **gerado a partir do código** (springdoc-openapi 3.2.1), então não fica desatualizado em relação às rotas: cada operação traz resumo, parâmetros, regras de idempotência, exemplos e **todos os códigos de resposta**, e as respostas de erro (`400`, `401`, `404`, `409`, `422`, `503`) apontam para o mesmo formato padrão de erro. Para guardar uma cópia: `curl http://localhost:8080/v3/api-docs > openapi.json`.

## Como funciona: o caminho de uma operação

Exemplo: `POST /wallets/21/transactions` com um crédito de 100,00.

```
Cliente (Postman, curl, outro sistema)
  |  HTTP, com o header X-API-Key (e Idempotency-Key nas operações)
  v
API Spring Boot (porta 8080)
  1. ApiKeyFilter ..... chave ausente ou errada: responde 401 e nem chega ao controller
  2. Controller ....... valida o corpo (tipo e valor): inválido, responde 400
  3. Service .......... abre a transação (@Transactional)
  4. Repository ....... chama o banco conectado como WALLET_APP
  |
  |  JDBC como WALLET_APP: só pode EXECUTE no PKG_WALLET e SELECT nas views
  v
Oracle (FREEPDB1)
  +-- WALLET_OWNER ------------------------------------------------------------+
  |  PKG_WALLET.REGISTRAR_OPERACAO                                              |
  |    a) trava a linha da carteira (FOR UPDATE, espera até 5 s; senão 503)     |
  |    b) a chave de idempotência já existe? mesmo conteúdo: devolve o          |
  |       resultado original (Idempotent-Replayed: true); outro conteúdo: 409   |
  |    c) débito maior que o saldo? 422, nada é alterado                        |
  |    d) grava a linha no extrato (LANCAMENTO) e atualiza o saldo (CARTEIRA)   |
  |    e) a trigger do saldo chama WALLET_AUDIT.PKG_AUDIT.REGISTRAR_SALDO       |
  +-----------------------------------------------------------------------------+
  +-- WALLET_AUDIT ------------------------------------------------------------+
  |  SALDO_AUDITORIA: uma linha por mudança de saldo (anterior, novo,           |
  |  operação que causou, usuário do banco e momento)                           |
  +-----------------------------------------------------------------------------+
  |
  v
API faz o COMMIT (o PKG_WALLET nunca faz commit) e responde 201
```

Dois pontos que explicam o desenho: o **lock vem antes** da checagem de idempotência (assim duas chamadas iguais e simultâneas não se atropelam), e o **COMMIT é da API**, então a operação, o saldo e a trilha de auditoria entram juntos ou nenhum entra.

## Papéis no Oracle: quem faz o quê

São **quatro usuários** no mesmo banco (`FREEPDB1`). Cada um enxerga só o que precisa (privilégio mínimo):

| Usuário | Função | Quem usa |
|---|---|---|
| `SYS` / `SYSTEM` (senha `ORACLE_PASSWORD`) | administrador do container | só o script de init, que cria os outros três. A API nunca o usa |
| `WALLET_OWNER` | **dono** das tabelas, sequences, package e triggers | só o Flyway (as migrations). A API nunca conecta com ele |
| `WALLET_APP` | **o usuário da aplicação** | a API Spring, em toda requisição |
| `WALLET_AUDIT` | **dono da trilha de auditoria** | ninguém no dia a dia |

**Quem enxerga o quê** (✓ pode, ✗ não pode):

| Objeto | `WALLET_APP` | `WALLET_OWNER` | `WALLET_AUDIT` |
|---|---|---|---|
| Tabelas `CARTEIRA` e `LANCAMENTO` | ✗ (nem leitura) | ✓ dono (mas o extrato é imutável por trigger) | ✗ |
| Views `V_CARTEIRA` e `V_EXTRATO` | ✓ só `SELECT` | ✓ dono | ✗ |
| `PKG_WALLET` (criar carteira, registrar operação) | ✓ `EXECUTE` | ✓ dono | ✗ |
| Tabela `SALDO_AUDITORIA` (a trilha) | ✗ | ✗ nem `INSERT` direto | ✓ dono |
| `PKG_AUDIT.REGISTRAR_SALDO` | ✗ | ✓ só `EXECUTE` | ✓ dono |

**O que cada um faz e não deixa fazer, com exemplos reais:**

| Usuário | Não deixa fazer | Exemplo prático |
|---|---|---|
| **`WALLET_APP`** | mexer em tabela, ler a auditoria | Se alguém tentar `UPDATE CARTEIRA SET SALDO = 1000000` conectado como `WALLET_APP` (um bug, ou uma falha de SQL injection), o Oracle responde `ORA-00942` (tabela inexistente para ele). O saldo só muda pelo `PKG_WALLET`, que valida tudo. Ele também não lê a trilha (`ORA-00942`) nem chama o `PKG_AUDIT` (`PLS-00201`) |
| **`WALLET_OWNER`** | alterar ou apagar o extrato, forjar a trilha, deixar saldo negativo | `UPDATE` ou `DELETE` em `LANCAMENTO` falha com `ORA-20005` (trigger), **mesmo sendo o dono**. `INSERT` direto na `SALDO_AUDITORIA` falha com `ORA-00942`: ele só consegue chamar a procedure. `UPDATE CARTEIRA SET SALDO = -1` falha com `ORA-02290` (`CHECK`) |
| **`WALLET_AUDIT`** | tocar nos dados da carteira | Não tem nenhum privilégio sobre as tabelas do `WALLET_OWNER`: a função dele é **só guardar a trilha** (tabela e procedure). Quem grava nela é a trigger, pela procedure |

**Situações comuns, do começo ao fim:**

| Situação | O que acontece |
|---|---|
| Crédito de 100,00 pela API | `WALLET_APP` executa o `PKG_WALLET`; o pacote (dono: `WALLET_OWNER`) grava o extrato e o saldo; a trigger chama o `WALLET_AUDIT`, que registra "saldo 0 → 100, usuário `WALLET_APP`". Tudo no mesmo `COMMIT` |
| O cliente repete o mesmo crédito (retry) | A chave de idempotência já existe: o pacote devolve o lançamento original, **sem alterar o saldo** e sem nova linha de auditoria. A API marca `Idempotent-Replayed: true` |
| Débito de 500,00 com saldo 70,00 | O pacote recusa com `-20003` (a API devolve `422`), nada é gravado e a trilha não muda |
| Alguém com acesso de dono altera o saldo por SQL direto (fora do pacote) | É permitido (ele é o dono), **mas deixa rastro**: a trigger grava na trilha uma linha com `OPERACAO_ID` **vazio**, o sinal de alteração suspeita |
| Alguém tenta apagar o histórico | `DELETE` em `LANCAMENTO`: `ORA-20005`, até para o dono |

**Limites assumidos** (detalhes no [DECISIONS.md](DECISIONS.md)): o dono do schema pode desabilitar uma trigger ou executar `TRUNCATE`, e o `WALLET_OWNER` pode chamar o `PKG_AUDIT` com valores falsos. Em produção, isso se cobre com a auditoria nativa do Oracle e controle de quem tem privilégio de DDL.

**Conexão para inspeção** (DBeaver, SQL Developer): host `localhost`, porta `1521`, **Service Name** `FREEPDB1`, **uma conexão por usuário**. Logar como cada um e rodar as consultas acima é a melhor forma de ver as diferenças.

## Estratégia de concorrência e idempotência (dentro do banco) e por quê

O enunciado pede que o saldo nunca fique negativo, que nenhuma operação seja aplicada em dobro e que o saldo final seja a soma consistente do que foi aplicado, **mesmo com chamadas simultâneas**. Tudo isso é resolvido no Oracle, em camadas:

| Camada | O que garante | Como |
|---|---|---|
| **Lock pessimista por carteira** | operações da mesma carteira entram **uma de cada vez**; carteiras diferentes não se bloqueiam | `PKG_WALLET.REGISTRAR_OPERACAO` começa com `SELECT ... FOR UPDATE WAIT 5` na linha da carteira. Quem não consegue o lock em 5 s recebe `ORA-20006` (a API devolve `503`, e o cliente pode tentar de novo) |
| **Idempotência checada depois do lock** | nenhuma operação é aplicada em dobro | com a carteira travada, o pacote procura a chave: mesma chave e mesmo conteúdo devolve o resultado original sem tocar no saldo; mesma chave com outro conteúdo é `ORA-20004` (`409`). A ordem importa: checando **antes** do lock, duas chamadas iguais e simultâneas poderiam passar juntas |
| **`UNIQUE (CARTEIRA_ID, CHAVE_IDEMPOTENCIA)`** | rede de segurança da idempotência | se algo escapasse, o próprio banco recusa a segunda linha |
| **`CHECK (SALDO >= 0)`** | o saldo **nunca** fica negativo, nem por SQL direto | última barreira, além da validação no pacote (`ORA-20003`, `422`) |
| **Transação da aplicação** | extrato, saldo e trilha de auditoria entram juntos ou nenhum entra | o `PKG_WALLET` nunca faz `COMMIT`; a API (`@Transactional`) confirma ao fim |

**Por que lock pessimista e não as alternativas:** (1) *versão otimista* (coluna de versão e retry) vira uma fila de retentativas quando muitas operações disputam a mesma carteira, que é justamente o caso do teste; (2) *isolamento `SERIALIZABLE`* faz o Oracle abortar transações com `ORA-08177`, empurrando o retry para a aplicação; (3) um `UPDATE ... WHERE SALDO >= :valor` sozinho protege o saldo, mas não resolve a idempotência nem a gravação atômica do extrato. O lock por carteira é simples de explicar e de provar. O custo assumido é que uma carteira muito disputada vira fila (limite de 5 s).

**Como isso é provado:** o `ConcorrenciaIT` dispara dezenas de chamadas simultâneas pela API contra o Oracle real (por exemplo, 30 débitos de 10 sobre saldo 100: exatamente 10 passam e 20 são recusados) e confere no banco que saldo, extrato e auditoria batem. Ao remover o `FOR UPDATE`, os 5 testes falham (ver [Como testar](#como-testar) e os itens 5 e 31 do [DECISIONS.md](DECISIONS.md)).

## Sequences

Os identificadores vêm de **sequences explícitas**, uma por tabela (`CARTEIRA`, `LANCAMENTO` e a trilha de auditoria): `START WITH 1 INCREMENT BY 1 CACHE 20 NOCYCLE`.

- **`CACHE 20`:** o Oracle reserva 20 números de cada vez em memória, o que evita ir ao dicionário a cada `NEXTVAL` sob concorrência. A consequência é que **podem surgir lacunas** (`1, 2, 3, 21...`) se o banco reiniciar ou se uma transação sofrer rollback. **Os ids são únicos e crescentes, mas não contínuos**, e nada no sistema depende de continuidade.
- **Por que não `NOCACHE`:** ele não elimina as lacunas (um rollback ainda consome o número) e custa em concorrência.
- **Crescentes:** o extrato usa o `id` como desempate de ordenação, então a ordem crescente importa (ver [Identificador da carteira](#identificador-da-carteira-número-no-teste-uuid-em-produção)).

## Premissas assumidas

O enunciado deixa a modelagem em aberto de propósito. Estas são as premissas, e o motivo de cada uma está no [DECISIONS.md](DECISIONS.md):

| Tema | Premissa adotada |
|---|---|
| **Moeda e precisão** | só BRL. Saldo e valor com 2 casas (`NUMBER(15,2)`); o valor da operação tem no máximo 13 dígitos inteiros e 2 casas, e é sempre maior que zero |
| **Titular** | opcional, texto de até 120 caracteres (não é documento fiscal) |
| **Identificadores** | números gerados por sequence, só para fins do teste (em produção, UUID) |
| **Chave de idempotência** | obrigatória no header `Idempotency-Key`, até 100 caracteres, única por carteira. Não faz parte do corpo porque identifica a **tentativa**, não o conteúdo |
| **Repetição** | a mesma chave com o mesmo conteúdo devolve `201` com o mesmo resultado e o header `Idempotent-Replayed: true`; com outro conteúdo, `409` |
| **Débito recusado** | `422`, **não deixa linha no extrato** (extrato só tem operações aplicadas) |
| **Extrato** | do mais recente para o mais antigo (data e depois `id`); período `[from, to)` com `from` inclusivo e `to` exclusivo, datas ISO-8601 com fuso; paginação `page` (a partir de 0) e `size` (1 a 100, padrão 20) |
| **Autenticação** | uma **API key única** da aplicação (`X-API-Key`), sem usuário final, JWT ou OAuth (fora de escopo). Só `GET /actuator/health` é público, além da documentação |
| **Quem controla a transação** | a aplicação, não o pacote PL/SQL |
| **Fora de escopo** | múltiplas moedas, câmbio, juros, estorno, agendamento e frontend, como o enunciado manda |

## O banco no Docker: como o container é preparado

O script [`db/init/01_usuarios.sh`](db/init/01_usuarios.sh) roda **só na primeira inicialização** (diretório de dados vazio) e cria os três usuários no PDB `FREEPDB1`. Para recriar do zero: `docker compose down -v && docker compose up -d` (**apaga os dados**).

As senhas vêm do `.env`, que **não** vai para o Git. A imagem usa tag fixa (`gvenzl/oracle-free:23.26.4-slim-faststart`). Os comandos para ligar o banco estão em [Subir o ambiente](#subir-o-ambiente-duas-maneiras).

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

## Modelo de dados

- **`CARTEIRA`**: `ID` (sequence), `TITULAR` (opcional), `SALDO NUMBER(15,2)` com `CHECK (SALDO >= 0)`, `CRIADA_EM`.
- **`LANCAMENTO`** (extrato): `ID`, `CARTEIRA_ID`, `TIPO` (`CREDITO` ou `DEBITO`), `VALOR > 0`, `SALDO_APOS`, `CHAVE_IDEMPOTENCIA`, `CRIADO_EM`. `UNIQUE (CARTEIRA_ID, CHAVE_IDEMPOTENCIA)`.
- **`SALDO_AUDITORIA`** (schema de auditoria): saldo anterior, saldo novo, operação que causou a mudança, usuário do banco e momento.

Triggers: o extrato é imutável (`UPDATE` e `DELETE` retornam `ORA-20005`, inclusive por SQL direto) e toda mudança de saldo grava a trilha na mesma transação.

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

**Cobertura:** `./mvnw verify` gera o relatório HTML em `target/site/jacoco/index.html` (linhas e ramos por classe) e falha se ficar abaixo do piso de 90% das linhas e 80% dos ramos.

## Integração contínua (CI)

Todo PR para `develop` e `main` roda o workflow [`ci.yml`](.github/workflows/ci.yml) (GitHub Actions, Java 21), em jobs encadeados:

```
Qualidade  ->  Testes unitários  ----\
           ->  Testes de integração  ->  Cobertura  ->  Build  ->  Build e testes (portão final)
```

| Job | O que roda |
|---|---|
| **Qualidade** | formatação (`spotless:check`), lint (`checkstyle:check`) e compilação estrita (`test-compile`: `-Xlint:all` com `failOnWarning`; em Java o typecheck é o compilador) |
| **Testes unitários** | os 122 testes (`./mvnw test`), sem banco e sem Docker |
| **Testes de integração** | os testes `*IT` contra um Oracle de verdade em Docker (Testcontainers), em paralelo com os unitários |
| **Cobertura** | JaCoCo junta a cobertura dos unitários e da integração e exige no mínimo **90% das linhas e 80% dos ramos** (hoje 97,6% e 88,1%) |
| **Build** | gera o jar executável (`./mvnw -DskipTests package`) |
| **Build e testes** | portão final: só passa se os cinco acima passarem |

O job `Build e testes` é o único **exigido pela proteção de branch**: sem ele verde, o merge é bloqueado. Para reproduzir tudo localmente: `./mvnw -B verify`.

Há ainda hooks de Git opcionais (`commit-msg` valida o padrão das mensagens; `pre-push` roda formato, lint e testes antes de enviar). Ativação: `git config core.hooksPath .githooks`.

Os padrões, as ferramentas, os testes que o CI exige e o motivo de cada escolha estão em [PADROES.md](PADROES.md).

## Provas de privilégio mínimo

Conectado como `WALLET_APP` (por exemplo no DBeaver): `INSERT`, `UPDATE`, `DELETE` ou `SELECT` direto nas tabelas, e qualquer acesso ao schema de auditoria, retornam `ORA-00942`. O teste `regras_pkg_wallet.sql` automatiza essa prova.

## Identificador da carteira: número no teste, UUID em produção

Nesta entrega o `id` da carteira (e do lançamento) é um **número** gerado por uma `SEQUENCE` do Oracle (`21`, `22`...). Isso é uma escolha **apenas para fins deste teste**: o extrato ordena por data e, no empate, pelo `id` crescente, e o desenho de concorrência (sequences com cache, `FOR UPDATE`, triggers) foi pensado em cima dele. Ids sequenciais são adivinháveis (`/wallets/21` convida a tentar o `22`) e revelam o volume de carteiras.

**Em produção, o identificador exposto pela API seria um UUID.** O caminho seria manter o número internamente (chave primária e ordenação) e acrescentar uma coluna `public_id` com UUID, usada nas rotas, ou usar UUID v7, que embute o tempo e preserva a ordem do extrato. Detalhes em [DECISIONS.md](DECISIONS.md) (item 35).

## O que ficou de fora (itens "plus" do enunciado)

Entregue: **todo o Núcleo obrigatório e todo o Perfil esperado** (7.1 e 7.2). Dos itens opcionais (7.3), **não** foram feitos, por escolha de escopo ("menor, bem-feito e bem-defendido"): teste de carga (k6/Gatling/JMeter), transferência entre carteiras, limite diário de débito, job de conciliação com `DBMS_SCHEDULER` e Dockerfile da aplicação. A observabilidade é a mínima: `health` e `info` do Actuator (sem métricas nem logs estruturados). O que eu faria com mais tempo, e em que ordem, está em [DECISIONS.md](DECISIONS.md). A consistência sob concorrência já é provada pelo `ConcorrenciaIT`, que cobre o ponto central do teste de carga (N operações simultâneas sobre a mesma carteira).
