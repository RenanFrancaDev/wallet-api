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

> Entram nas próximas etapas: JaCoCo (cobertura mínima), o CI separado em jobs e um hook de `pre-push`.

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
