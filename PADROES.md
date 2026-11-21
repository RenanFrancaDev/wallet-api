# Padrões de código e portões de qualidade

Tudo aqui é **automatizado e verificado no CI**: ninguém discute estilo em revisão de código, e um PR só entra se passar nos portões abaixo. Este documento cresce a cada ferramenta adicionada.

## Resumo

| O que | Ferramenta | Equivalente no Node | Como rodar | Falha o CI? |
|---|---|---|---|---|
| Formatação | Spotless + google-java-format (estilo AOSP) | Prettier / Biome (format) | `./mvnw spotless:check` (confere) e `./mvnw spotless:apply` (corrige) | sim |
| Tipos e avisos do compilador | `javac` com `-Xlint:all` e `failOnWarning` | `tsc --noEmit` | `./mvnw test-compile` | sim |
| Testes unitários | JUnit 5 + Mockito | jest | `./mvnw test` | sim |

Um único comando roda tudo, na ordem do CI: `./mvnw verify`.

> Entram nas próximas etapas: Checkstyle (regras de estilo), JaCoCo (cobertura mínima), o CI separado em jobs e um hook de `pre-push`.

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

## Como trabalhar no dia a dia

1. Escreva o código normalmente.
2. Antes de commitar: `./mvnw spotless:apply`.
3. Antes de abrir o PR: `./mvnw verify`. É o que o CI roda; se passar aí, passa lá.

Se o CI reclamar de formatação, ele mostra o arquivo e o diff do que mudaria. A correção é sempre rodar `./mvnw spotless:apply` e commitar.

## Histórico da adoção

A adoção do formatador foi feita em dois commits separados: primeiro `style:` (só formatação, nenhuma mudança de comportamento, e os 48 testes continuam passando) e depois `build:` (o plugin que passa a exigir o formato). Assim o commit de reformatação é fácil de revisar e de ignorar em um `git blame`.
