# Padrões de código e portões de qualidade

Tudo aqui é **automatizado e verificado no CI**: ninguém discute estilo em revisão de código, e um PR só entra se passar nos portões abaixo. Este documento cresce a cada ferramenta adicionada.

## Resumo

| O que | Ferramenta | Equivalente no Node | Como rodar | Falha o CI? |
|---|---|---|---|---|
| Formatação | Spotless + google-java-format (estilo AOSP) | Prettier / Biome (format) | `./mvnw spotless:check` (confere) e `./mvnw spotless:apply` (corrige) | sim |
| Lint de estilo e bugs comuns | Checkstyle | ESLint / Biome (lint) | `./mvnw checkstyle:check` | sim |
| Tipos e avisos do compilador | `javac` com `-Xlint:all` e `failOnWarning` | `tsc --noEmit` | `./mvnw test-compile` | sim |
| Testes unitários | JUnit 5 + Mockito | jest | `./mvnw test` | sim |

Um único comando roda tudo, na ordem do CI: `./mvnw verify`.

> Entra na próxima etapa: JaCoCo (cobertura mínima).

## O que o CI exige (e o que cada job faz)

O workflow [`ci.yml`](.github/workflows/ci.yml) roda em todo PR e push para `develop` e `main`, em jobs encadeados. Cada um falha e aparece no PR de forma independente, então dá para ver de relance onde quebrou:

```
Qualidade  ->  Testes unitários  ----\
           ->  Testes de integração  ->  Build  ->  Build e testes (portão final)
```

| Job | O que roda | Falha quando | Como reproduzir |
|---|---|---|---|
| **Qualidade (formato, lint e tipos)** | `spotless:check`, `checkstyle:check`, `test-compile` | arquivo sem formatar, violação de lint ou aviso do compilador | `./mvnw spotless:check checkstyle:check test-compile` |
| **Testes unitários** (só roda se a qualidade passar) | `./mvnw test`; resumo na página da execução e relatórios guardados por 7 dias | qualquer teste falha | `./mvnw test` |
| **Testes de integração (Oracle real)** (só roda se a qualidade passar; em paralelo com os unitários) | `test-compile failsafe:integration-test failsafe:verify`: sobe um Oracle em Docker (Testcontainers) e roda as classes `*IT`; relatórios guardados por 7 dias | qualquer teste `*IT` falha, ou o banco não sobe | `./mvnw test-compile failsafe:integration-test failsafe:verify` (exige Docker) |
| **Build** (só roda se os testes passarem) | `./mvnw -DskipTests package` | o jar executável não é gerado | `./mvnw -DskipTests package` |
| **Build e testes** (portão final) | confere o resultado dos quatro acima | qualquer um não for `success` | `./mvnw verify` (exige Docker) |

**Por que jobs separados (e não um só):** o mesmo padrão dos pipelines dos projetos Node (lint, typecheck, testes e build como etapas distintas). Quem abre o PR vê em segundos se o problema é de formato, de lint, de teste ou de empacotamento, e a ordem economiza tempo: não se gasta o runner rodando testes em código que nem passa na qualidade.

**Por que um job final com nome fixo:** a proteção de branch exige apenas o check `Build e testes`. Como ele só passa se todos os outros passarem, os jobs podem ser renomeados ou ganhar novos colegas sem mexer na regra do GitHub. Sem isso, renomear um job deixaria um check obrigatório que nunca aparece, e nenhum PR poderia ser mergeado.

**Os testes que precisam passar hoje:** os 122 testes unitários (JUnit 5 e Mockito), sem banco e sem Docker:

| Classe | Testes | O que prova |
|---|---|---|
| `ErroNegocioTest` | 13 | catálogo: cada código `ORA-20001` a `20006` vira o erro e o status HTTP certos |
| `GlobalExceptionHandlerTest` | 14 | tradução de erros: saldo insuficiente vira 422, a mensagem do Oracle não vaza, erros inesperados viram 500 |
| `CarteiraServiceTest` | 5 | regras do serviço (criar e consultar, carteira inexistente) |
| `CarteiraControllerTest` | 10 | camada web: status, `Location`, validação e formato padrão de erro |
| `CarteiraTest` | 6 | o saldo sempre sai com 2 casas decimais |
| `LancamentoControllerTest` | 12 | registrar operação: `201`, repetição com `Idempotent-Replayed`, header ausente, tipo e valor inválidos, e saldo insuficiente (422), chave conflitante (409) e carteira inexistente (404) vindos do banco |
| `LancamentoServiceTest` | 3 | repasse ao repositório, repetição marcada, erros do banco não engolidos |
| `LancamentoTest` | 3 | valor e saldo do lançamento sempre com 2 casas |
| `ExtratoControllerTest` | 7 | extrato: padrões (página 0, tamanho 20), repasse de `page`, `size`, `from` e `to`, data e número inválidos (400), validação com motivo nos detalhes, carteira inexistente (404) |
| `ExtratoServiceTest` | 13 | validação (`page`, `size`, `from` depois de `to`), carteira inexistente sem consultar o extrato, deslocamento = página x tamanho, extrato vazio |
| `PaginaTest` | 6 | cálculo do total de páginas |
| `ApiKeyFilterTest` | 12 | API key (inclui que a documentação OpenAPI e o Swagger UI são públicos e caminhos parecidos não): chave certa passa; sem header, errada, vazia ou quase certa dão `401` no formato padrão e sem chegar ao serviço; mensagem igual nos dois casos; escrita e rota inexistente também barradas; só a saúde é pública (e `/actuator/healthz` não) |
| `ApiKeyConfigTest` | 6 | falha fechada: chave nula, vazia ou com menos de 16 caracteres impede a inicialização |
| `OpenApiConfigTest` | 4 | o contrato declara a API key no header `X-API-Key` e a exige em todas as operações |
| `RespostasDeErroCustomizerTest` | 8 | todo `4xx`/`5xx` aponta para o formato padrão de erro (inclusive quando o springdoc anexa o corpo de sucesso por engano), `401` em toda operação, respostas de sucesso intocadas |

**Testes de integração (Oracle real)**, em `src/test/java/.../integracao/`, rodados pelo *failsafe* (classes `*IT`, fase `verify`), separados dos unitários para `./mvnw test` continuar rápido e sem Docker:

| Classe | Testes | O que prova |
|---|---|---|
| `BancoIT` | 10 | migrations dos dois schemas aplicadas (6 e 2) e nenhum objeto inválido; o `WALLET_APP` não tem DML nem leitura das tabelas e não acessa a auditoria (`ORA-00942`, `PLS-00201`); lê as views e executa o package; o dono não escreve direto na trilha; extrato imutável até para o dono (`ORA-20005`); `CHECK` de saldo (`ORA-02290`); a trilha registra saldo anterior, novo, operação e usuário, e uma alteração por SQL direto fica sem operação |

O container é **o mesmo** do `docker-compose.yml` (mesma imagem com tag fixa) e os três usuários são criados pelo **mesmo** script `db/init/01_usuarios.sh`: se a imagem ou o script mudar, o teste acompanha.

| `OperacoesIT` | 6 | a API de ponta a ponta por HTTP real: criar e consultar (saldo `0.00`), crédito, repetição idempotente (mesmo `id` e `Idempotent-Replayed`), conflito `409`, débito, saldo insuficiente `422` (sem criar linha no extrato), extrato paginado e com período, validações `400` com o motivo, `404`, e `401` sem a API key com a saúde pública |
| `ConcorrenciaIT` | 5 | **concorrência em Java**, por HTTP real, com threads soltas juntas por uma barreira (`CountDownLatch`): 30 débitos simultâneos sobre saldo 100 aplicam exatamente 10; 20 chamadas iguais aplicam uma vez só e as outras 19 devolvem o resultado original; mesma chave com conteúdos diferentes deixa vencer um só; créditos e débitos misturados e várias carteiras ao mesmo tempo mantêm as invariantes. A verdade é conferida **direto no banco** (saldo, soma do extrato, auditoria encadeada) |
| `OpenApiIT` | 4 | o contrato OpenAPI realmente gerado: público, com a `X-API-Key`, todo `4xx` e `5xx` apontando para o formato padrão de erro, sem o tipo de mídia genérico `*/*` |

São **25 testes de integração** no total.

**Teste do teste:** para provar que o `ConcorrenciaIT` detecta o defeito e não só passa, removi temporariamente o `FOR UPDATE` do `PKG_WALLET` e rodei só ele: o resultado está no DECISIONS.md (item 31).

## Hooks locais (opcional, recomendado)

Equivalem ao Husky dos projetos Node. Rodam na sua máquina **antes** do CI, para você descobrir o problema em segundos e não depois do push. Ativação, uma vez por clone:

```bash
git config core.hooksPath .githooks
```

| Hook | O que faz |
|---|---|
| **`commit-msg`** | valida a mensagem no padrão `tipo(escopo): descrição` (`feat`, `fix`, `chore`, `docs`, `test`, `style`, `refactor`, `perf`, `build`, `ci`, `revert`), com até 100 caracteres na primeira linha. Merges e reverts do Git passam |
| **`pre-push`** | roda formato, lint e testes (`spotless:check checkstyle:check test`) antes de enviar |

Para pular em emergência: `git push --no-verify`. O CI continua bloqueando o merge, então o hook é uma conveniência, não a única barreira.

## Formatação

**Ferramenta:** [Spotless](https://github.com/diffplug/spotless) executando o [google-java-format](https://github.com/google/google-java-format).

**Por que essa:**

- É o formatador mais usado no ecossistema Java, e o Spotless é o jeito padrão de ligá-lo ao Maven.
- **Não é configurável de propósito**, como o Prettier: o mesmo código sempre sai com o mesmo formato, e a discussão de estilo acaba.
- Corrige em vez de só reclamar: `spotless:apply` arruma tudo de uma vez.

**Escolhas que fiz:**

| Escolha | Motivo |
|---|---|
| Estilo **AOSP** (a variante do google-java-format com 4 espaços), e não o padrão Google (2 espaços) | O código do projeto já usava 4 espaços; manter evitou reformatar a indentação inteira e deixou o diff da adoção menor |
| Linhas de até **100 colunas** | Padrão do formatador; força comentários e expressões longas a quebrarem de forma legível |
| Imports ordenados e **sem imports sem uso** | Feito pelo próprio formatador, não por convenção |
| Fim de linha sempre **LF**, em qualquer sistema operacional (`lineEndings=UNIX` no Spotless e `eol=lf` no `.gitattributes`) | No Windows o padrão seria CRLF; sem isso o formatador reescrevia todos os arquivos e gerava ruído no Git. Foi um problema real na adoção |

**Limitação conhecida:** o google-java-format não suporta tabulação. Projetos em Node que usam tab (por exemplo, com Biome) não têm equivalente direto aqui.

## Lint (Checkstyle)

**Ferramenta:** [Checkstyle](https://checkstyle.org), a mais usada em Java para regras de estilo e bugs comuns. Configuração em [`config/checkstyle/checkstyle.xml`](config/checkstyle/checkstyle.xml), com o motivo de cada regra no próprio arquivo.

**Divisão de trabalho com o formatador (importante):** o Spotless cuida de **formato** (espaços, chaves, ordem de imports); o Checkstyle cuida de **regras**. O arquivo de regras **não** inclui nada de formato, porque duas ferramentas decidindo a mesma coisa acabam em conflito (o preset do Google no Checkstyle, por exemplo, exige 2 espaços e brigaria com o estilo AOSP).

**Conjunto enxuto, regra a regra**, em vez de um preset gigante que gera ruído e acaba ignorado:

| Grupo | Regras | Por quê |
|---|---|---|
| Imports | sem `*`, sem import sem uso, sem import redundante ou de classe interna do JDK | `*` esconde a origem das classes; `sun.*` quebra entre versões do Java |
| Nomes | pacote, tipo, método, campo, parâmetro, variável local, constante | convenções do Java, para o código ser previsível |
| Bugs comuns | `equals` sem `hashCode`, `==` em `String`, `switch` sem `default`, `fall through`, blocos e comandos vazios, variável local sem uso, expressões booleanas redundantes | erros clássicos que o compilador aceita |
| Estilo seguro | `if/for/while` sempre com chaves, um comando por linha, sem tabulação, arquivo terminando em LF | evita bugs ao editar depois e ruído no Git |
| Complexidade | método com até 80 linhas, até 8 parâmetros | método enorme é sinal de que precisa ser dividido |
| Logs | proibido `System.out`, `System.err` e `printStackTrace` | equivale ao `noConsole` do Biome; o logger (SLF4J) tem nível, formato e destino configuráveis |
| Tamanho de linha | teto de 120 colunas | o formatador quebra em 100, mas não quebra strings longas; esta regra pega o que ele não alcança |

**Decisão sobre falhas:** qualquer violação falha o build (sem "aviso que ninguém lê"). O lint também roda nos testes.

**O que encontrou na adoção:** a constante do logger em `GlobalExceptionHandler` se chamava `log` (constante `static final` pede `MAIÚSCULA`, agora `LOGGER`) e duas linhas de teste passavam de 120 colunas.

**Não incluídas, de propósito:** exigir Javadoc em tudo (ruído), `MagicNumber` (muito barulhento em testes) e `HideUtilityClassConstructor` (a classe principal do Spring seria sinalizada sem ganho). Análise de bugs mais profunda (SpotBugs, PMD, Error Prone) fica como evolução.

## Como trabalhar no dia a dia

1. Escreva o código normalmente.
2. Antes de commitar: `./mvnw spotless:apply` (corrige o formato sozinho).
3. Antes de abrir o PR: `./mvnw verify`. É o que o CI roda; se passar aí, passa lá.

Se o CI reclamar de **formatação**, ele mostra o arquivo e o diff do que mudaria: rode `./mvnw spotless:apply` e commite. Se reclamar de **lint**, a mensagem traz arquivo, linha e o nome da regra (por exemplo `[ConstantName]`); o motivo de cada regra está em `config/checkstyle/checkstyle.xml`. O Checkstyle não corrige sozinho, então a correção é manual.

## Histórico da adoção

A adoção do formatador foi feita em dois commits separados: primeiro `style:` (só formatação, nenhuma mudança de comportamento, e os 48 testes continuam passando) e depois `build:` (o plugin que passa a exigir o formato). Assim o commit de reformatação é fácil de revisar e de ignorar em um `git blame`.
