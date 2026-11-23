#!/bin/bash
# Teste de concorrencia do PKG_WALLET contra o Oracle real.
# Abre varias sessoes sqlplus AO MESMO TEMPO, todas como WALLET_APP (o usuario da aplicacao),
# e confere se o banco manteve as garantias: saldo nunca negativo, nenhuma operacao em dobro,
# saldo final = soma do extrato, e trilha de auditoria encadeada sem buracos.
#
# Roda DENTRO do container (as senhas ja estao nas variaveis de ambiente dele).
# Pre-requisito: migrations audit e owner aplicadas.
#
# Como rodar (Git Bash no Windows precisa de MSYS_NO_PATHCONV=1):
#   docker compose exec -T oracle bash -c "$(cat db/tests/concorrencia_pkg_wallet.sh)"
#
# Parametro opcional: N_SESSOES (padrao 20), ex.: docker compose exec -T -e N_SESSOES=40 oracle ...
# O codigo de saida e o numero de verificacoes que falharam (0 = tudo ok).

set -u
N=${N_SESSOES:-20}
DB="//localhost:1521/FREEPDB1"
APP="WALLET_APP/${WALLET_APP_PASSWORD}@${DB}"
OWN="WALLET_OWNER/${WALLET_OWNER_PASSWORD}@${DB}"
AUD="WALLET_AUDIT/${WALLET_AUDIT_PASSWORD}@${DB}"
TMP=$(mktemp -d)
FALHAS=0

check() {
  # check "descricao" esperado obtido
  if [ "$2" = "$3" ]; then
    echo "  PASS  $1 (= $3)"
  else
    echo "  FAIL  $1 (esperado $2, obtido $3)"
    FALHAS=$((FALHAS + 1))
  fi
}

# Consulta de um valor so, como o usuario informado ($1), SQL em $2.
valor() {
  # tr remove espacos e tabulacoes: o sqlplus alinha numeros com padding.
  sqlplus -s "$1" <<EOF | grep -v '^[[:space:]]*$' | tail -1 | tr -d '[:space:]'
SET HEADING OFF FEEDBACK OFF PAGESIZE 0 DEFINE OFF
$2
EOF
}

# Cria uma carteira com credito inicial ($1 = valor) e imprime o id.
nova_carteira() {
  sqlplus -s "$APP" <<EOF | grep -E '^[0-9]+$' | tail -1
SET SERVEROUTPUT ON FEEDBACK OFF DEFINE OFF
DECLARE
  c NUMBER; s NUMBER; t TIMESTAMP WITH TIME ZONE; l NUMBER; a NUMBER; e TIMESTAMP WITH TIME ZONE; r NUMBER;
BEGIN
  WALLET_OWNER.PKG_WALLET.CRIAR_CARTEIRA('teste-concorrencia', c, s, t);
  WALLET_OWNER.PKG_WALLET.REGISTRAR_OPERACAO(c, 'CREDITO', $1, 'carga-inicial', l, a, e, r);
  COMMIT;
  DBMS_OUTPUT.PUT_LINE(c);
END;
/
EOF
}

# Uma sessao (conexao propria): tipo, valor, chave, arquivo de saida. A carteira e a variavel $CART.
sessao() {
  sqlplus -s "$APP" > "$4" <<EOF
SET SERVEROUTPUT ON FEEDBACK OFF DEFINE OFF
DECLARE
  l NUMBER; a NUMBER; e TIMESTAMP WITH TIME ZONE; r NUMBER;
BEGIN
  WALLET_OWNER.PKG_WALLET.REGISTRAR_OPERACAO($CART, '$1', $2, '$3', l, a, e, r);
  COMMIT;
  DBMS_OUTPUT.PUT_LINE('OK repetida=' || r);
EXCEPTION WHEN OTHERS THEN
  ROLLBACK;
  DBMS_OUTPUT.PUT_LINE('ERRO ' || SQLCODE);
END;
/
EOF
}

conta() { cat "$TMP"/$1_*.out 2>/dev/null | grep -c "$2" ; }

verifica_banco() {
  # saldo final, soma do extrato e cadeia da auditoria para a carteira $CART
  SALDO=$(valor "$OWN" "SELECT SALDO FROM CARTEIRA WHERE ID = $CART;")
  SOMA=$(valor "$OWN" "SELECT NVL(SUM(CASE TIPO WHEN 'CREDITO' THEN VALOR ELSE -VALOR END), 0) FROM LANCAMENTO WHERE CARTEIRA_ID = $CART;")
  # cadeia: o saldo_anterior de cada linha deve ser o saldo_novo da linha anterior
  QUEBRAS=$(valor "$AUD" "SELECT COUNT(*) FROM (SELECT SALDO_ANTERIOR, LAG(SALDO_NOVO) OVER (ORDER BY ID) AS ANT FROM SALDO_AUDITORIA WHERE CARTEIRA_ID = $CART) WHERE ANT IS NOT NULL AND ANT <> SALDO_ANTERIOR;")
  N_LANC=$(valor "$OWN" "SELECT COUNT(*) FROM LANCAMENTO WHERE CARTEIRA_ID = $CART;")
  N_AUD=$(valor "$AUD" "SELECT COUNT(*) FROM SALDO_AUDITORIA WHERE CARTEIRA_ID = $CART;")
  ULT_AUD=$(valor "$AUD" "SELECT SALDO_NOVO FROM (SELECT SALDO_NOVO FROM SALDO_AUDITORIA WHERE CARTEIRA_ID = $CART ORDER BY ID DESC) WHERE ROWNUM = 1;")
}

# ---------------------------------------------------------------------------------------------
echo "=== Cenario 1: $N debitos de 10 ao mesmo tempo numa carteira com saldo 100 ==="
CART=$(nova_carteira 100)
echo "  carteira $CART"
for i in $(seq 1 "$N"); do sessao DEBITO 10 "deb-$i" "$TMP/c1_$i.out" & done
wait
APLICADOS=$(conta c1 '^OK repetida=0')
RECUSADOS=$(conta c1 '^ERRO -20003')
OUTROS=$((N - APLICADOS - RECUSADOS))
verifica_banco
check "debitos aplicados (saldo 100 / valor 10)" 10 "$APLICADOS"
check "debitos recusados por saldo insuficiente (-20003)" $((N - 10)) "$RECUSADOS"
check "respostas inesperadas" 0 "$OUTROS"
check "saldo final nunca negativo e igual a 0" 0 "$SALDO"
check "saldo final = soma do extrato" "$SOMA" "$SALDO"
check "lancamentos = 1 credito inicial + 10 debitos" 11 "$N_LANC"
check "linhas de auditoria = 1 credito + 10 debitos" 11 "$N_AUD"
check "ultimo saldo da auditoria = saldo da carteira" "$SALDO" "$ULT_AUD"
check "quebras na cadeia da auditoria" 0 "$QUEBRAS"

# ---------------------------------------------------------------------------------------------
echo "=== Cenario 2: $N chamadas IGUAIS (mesma chave, mesmo payload) ao mesmo tempo ==="
CART=$(nova_carteira 0.01)
echo "  carteira $CART"
for i in $(seq 1 "$N"); do sessao CREDITO 50 "mesma-chave" "$TMP/c2_$i.out" & done
wait
NOVAS=$(conta c2 '^OK repetida=0')
REPETIDAS=$(conta c2 '^OK repetida=1')
verifica_banco
check "aplicada uma unica vez" 1 "$NOVAS"
check "demais respondem com o resultado original (repetida=1)" $((N - 1)) "$REPETIDAS"
check "saldo = 0.01 + 50 (sem dobro)" "50.01" "$SALDO"
check "lancamentos = carga inicial + 1" 2 "$N_LANC"
check "saldo final = soma do extrato" "$SOMA" "$SALDO"
check "quebras na cadeia da auditoria" 0 "$QUEBRAS"

# ---------------------------------------------------------------------------------------------
echo "=== Cenario 3: mesma chave com payloads DIFERENTES ao mesmo tempo (metade credito, metade debito) ==="
CART=$(nova_carteira 100)
echo "  carteira $CART"
for i in $(seq 1 "$N"); do
  if [ $((i % 2)) -eq 0 ]; then T=CREDITO; else T=DEBITO; fi
  sessao "$T" 10 "chave-disputada" "$TMP/c3_$i.out" &
done
wait
NOVAS=$(conta c3 '^OK repetida=0')
REPETIDAS=$(conta c3 '^OK repetida=1')
CONFLITOS=$(conta c3 '^ERRO -20004')
verifica_banco
check "somente um payload venceu" 1 "$NOVAS"
check "respostas validas (repetidas + conflitos) = demais" $((N - 1)) $((REPETIDAS + CONFLITOS))
check "houve conflitos (-20004) para o payload perdedor" "sim" "$([ "$CONFLITOS" -gt 0 ] && echo sim || echo nao)"
check "lancamentos = carga inicial + 1" 2 "$N_LANC"
check "saldo final = soma do extrato" "$SOMA" "$SALDO"
check "quebras na cadeia da auditoria" 0 "$QUEBRAS"

# ---------------------------------------------------------------------------------------------
[ -n "$TMP" ] && rm -rf "$TMP"
echo "------"
if [ "$FALHAS" -eq 0 ]; then
  echo "RESULTADO: todas as verificacoes passaram"
else
  echo "RESULTADO: $FALHAS verificacao(oes) falharam"
fi
exit "$FALHAS"
