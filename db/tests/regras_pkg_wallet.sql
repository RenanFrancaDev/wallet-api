-- Testes das regras do PKG_WALLET, executados como WALLET_APP (o usuario real da aplicacao).
-- Pre-requisito: migrations audit e owner aplicadas.
--
-- Como rodar (Git Bash no Windows precisa de MSYS_NO_PATHCONV=1):
--   docker compose exec -T oracle sh -c 'sqlplus -s WALLET_APP/"$WALLET_APP_PASSWORD"@//localhost:1521/FREEPDB1' < db/tests/regras_pkg_wallet.sql
--
-- Cada teste imprime PASS ou FAIL. O bloco final falha (ORA-20999) se algum teste falhou.

SET SERVEROUTPUT ON
SET DEFINE OFF
SET SQLBLANKLINES ON

DECLARE
  g_cart   NUMBER;
  g_main   NUMBER; -- carteira principal dos testes (g_cart e reutilizada em outros testes)
  g_saldo  NUMBER;
  g_criada TIMESTAMP WITH TIME ZONE;
  g_lanc   NUMBER;
  g_apos   NUMBER;
  g_em     TIMESTAMP WITH TIME ZONE;
  g_rep    NUMBER;
  g_code   NUMBER;
  g_lanc1  NUMBER;
  g_falhas PLS_INTEGER := 0;
  g_n      NUMBER;
  g_soma   NUMBER;
  g_chave101 VARCHAR2(200) := RPAD('x', 101, 'x');

  PROCEDURE verifica(p_nome IN VARCHAR2, p_ok IN BOOLEAN) IS
  BEGIN
    IF p_ok THEN
      DBMS_OUTPUT.PUT_LINE('PASS  ' || p_nome);
    ELSE
      DBMS_OUTPUT.PUT_LINE('FAIL  ' || p_nome);
      g_falhas := g_falhas + 1;
    END IF;
  END;

  -- Chama a operacao e guarda o codigo de erro (0 = sem erro).
  PROCEDURE op(p_cart IN NUMBER, p_tipo IN VARCHAR2, p_valor IN NUMBER, p_chave IN VARCHAR2) IS
  BEGIN
    g_code := 0;
    WALLET_OWNER.PKG_WALLET.REGISTRAR_OPERACAO(p_cart, p_tipo, p_valor, p_chave, g_lanc, g_apos, g_em, g_rep);
  EXCEPTION
    WHEN OTHERS THEN
      g_code := SQLCODE;
  END;

  FUNCTION saldo_atual(p_cart IN NUMBER) RETURN NUMBER IS
    v NUMBER;
  BEGIN
    SELECT SALDO INTO v FROM WALLET_OWNER.V_CARTEIRA WHERE ID = p_cart;
    RETURN v;
  END;
BEGIN
  -- Carteira
  WALLET_OWNER.PKG_WALLET.CRIAR_CARTEIRA('Maria', g_cart, g_saldo, g_criada);
  verifica('criar carteira devolve id e saldo inicial 0', g_cart IS NOT NULL AND g_saldo = 0 AND g_criada IS NOT NULL);
  g_main := g_cart;

  -- Credito e idempotencia
  op(g_cart, 'CREDITO', 100, 'c1');
  verifica('credito 100 -> saldo_apos 100, nao repetida', g_code = 0 AND g_apos = 100 AND g_rep = 0);
  g_lanc1 := g_lanc;

  op(g_cart, 'CREDITO', 100, 'c1');
  verifica('mesma chave e mesmo payload -> repetida, mesmo lancamento', g_code = 0 AND g_rep = 1 AND g_lanc = g_lanc1);
  verifica('retry nao altera o saldo', saldo_atual(g_cart) = 100);

  op(g_cart, 'CREDITO', 50, 'c1');
  verifica('mesma chave, valor diferente -> -20004', g_code = -20004);
  op(g_cart, 'DEBITO', 100, 'c1');
  verifica('mesma chave, tipo diferente -> -20004', g_code = -20004);
  verifica('conflito nao altera o saldo', saldo_atual(g_cart) = 100);

  -- Debito e saldo insuficiente
  op(g_cart, 'DEBITO', 30, 'd1');
  verifica('debito 30 -> saldo_apos 70', g_code = 0 AND g_apos = 70);
  op(g_cart, 'DEBITO', 70.01, 'd2');
  verifica('debito acima do saldo -> -20003', g_code = -20003);
  verifica('debito recusado nao altera o saldo', saldo_atual(g_cart) = 70);
  op(g_cart, 'DEBITO', 70.01, 'd2');
  verifica('retry de debito recusado reavalia (continua -20003)', g_code = -20003);
  op(g_cart, 'DEBITO', 70, 'd3');
  verifica('debito do saldo inteiro e permitido (saldo 0)', g_code = 0 AND g_apos = 0 AND saldo_atual(g_cart) = 0);
  op(g_cart, 'DEBITO', 0.01, 'd4');
  verifica('debito com saldo zero -> -20003', g_code = -20003);

  -- Validacoes
  op(g_cart, 'CREDITO', 0, 'v1');
  verifica('valor zero -> -20002', g_code = -20002);
  op(g_cart, 'CREDITO', -5, 'v2');
  verifica('valor negativo -> -20002', g_code = -20002);
  op(g_cart, 'CREDITO', 10.005, 'v3');
  verifica('3 casas decimais -> -20002 (sem arredondar em silencio)', g_code = -20002);
  op(g_cart, 'CREDITO', 10000000000000, 'v4');
  verifica('valor acima do maximo -> -20002', g_code = -20002);
  op(g_cart, 'PIX', 10, 'v5');
  verifica('tipo invalido -> -20002', g_code = -20002);
  op(g_cart, NULL, 10, 'v6');
  verifica('tipo nulo -> -20002', g_code = -20002);
  op(g_cart, 'CREDITO', NULL, 'v7');
  verifica('valor nulo -> -20002', g_code = -20002);
  op(g_cart, 'CREDITO', 10, NULL);
  verifica('chave nula -> -20002', g_code = -20002);
  op(g_cart, 'CREDITO', 10, '   ');
  verifica('chave so com espacos -> -20002', g_code = -20002);
  op(g_cart, 'CREDITO', 10, g_chave101);
  verifica('chave com 101 caracteres -> -20002', g_code = -20002);
  op(NULL, 'CREDITO', 10, 'v8');
  verifica('carteira nula -> -20002', g_code = -20002);
  op(-1, 'CREDITO', 10, 'v9');
  verifica('carteira inexistente -> -20001', g_code = -20001);

  -- Titular
  BEGIN
    WALLET_OWNER.PKG_WALLET.CRIAR_CARTEIRA(RPAD('t', 121, 't'), g_cart, g_saldo, g_criada);
    g_code := 0;
  EXCEPTION WHEN OTHERS THEN g_code := SQLCODE;
  END;
  verifica('titular com 121 caracteres -> -20002', g_code = -20002);

  -- Extrato: leitura pela view, consistente com as operacoes aplicadas
  -- (a carteira usada acima: credito 100, debito 30, debito 70)
  SELECT COUNT(*), SUM(CASE TIPO WHEN 'CREDITO' THEN VALOR ELSE -VALOR END)
    INTO g_n, g_soma
    FROM WALLET_OWNER.V_EXTRATO
   WHERE CARTEIRA_ID = g_main;
  verifica('extrato tem 3 lancamentos (recusas e repeticoes nao entram)', g_n = 3);
  verifica('soma do extrato = saldo final (0)', g_soma = 0);

  -- Privilegio minimo: o APP nao escreve nem le tabelas, nem toca na auditoria
  BEGIN
    EXECUTE IMMEDIATE 'INSERT INTO WALLET_OWNER.CARTEIRA (ID) VALUES (999999)';
    g_code := 0;
  EXCEPTION WHEN OTHERS THEN g_code := SQLCODE;
  END;
  verifica('APP: INSERT direto na tabela -> ORA-00942', g_code = -942);

  BEGIN
    EXECUTE IMMEDIATE 'UPDATE WALLET_OWNER.CARTEIRA SET SALDO = 999999';
    g_code := 0;
  EXCEPTION WHEN OTHERS THEN g_code := SQLCODE;
  END;
  verifica('APP: UPDATE direto na tabela -> ORA-00942', g_code = -942);

  BEGIN
    EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM WALLET_AUDIT.SALDO_AUDITORIA' INTO g_n;
    g_code := 0;
  EXCEPTION WHEN OTHERS THEN g_code := SQLCODE;
  END;
  verifica('APP: ler a trilha de auditoria -> ORA-00942', g_code = -942);

  ROLLBACK;
  DBMS_OUTPUT.PUT_LINE('------');
  IF g_falhas = 0 THEN
    DBMS_OUTPUT.PUT_LINE('RESULTADO: todos os testes passaram');
  ELSE
    DBMS_OUTPUT.PUT_LINE('RESULTADO: ' || g_falhas || ' teste(s) falharam');
  END IF;
END;
/
