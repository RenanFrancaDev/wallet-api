-- Executada pelo Flyway conectado como WALLET_OWNER.
-- PKG_WALLET: unica porta de escrita da aplicacao. Toda regra de consistencia vive aqui.
--
-- Erros de negocio (RAISE_APPLICATION_ERROR, faixa -20000 a -20999):
--   -20001  carteira nao encontrada
--   -20002  dados invalidos (tipo, valor, chave, titular)
--   -20003  saldo insuficiente
--   -20004  chave de idempotencia reutilizada com payload diferente
--   -20005  extrato imutavel (lancada pela trigger TRG_LANCAMENTO_IMUTAVEL)
--   -20006  carteira ocupada (nao foi possivel obter o lock a tempo)
--
-- Controle de transacao: as procedures NAO fazem COMMIT nem ROLLBACK.
-- Quem chama (a aplicacao, via @Transactional) decide. Uma chamada que termina em
-- excecao nao deixa efeito parcial: o Oracle desfaz as alteracoes daquela chamada.

CREATE OR REPLACE PACKAGE PKG_WALLET AUTHID DEFINER AS

  c_err_carteira_inexistente  CONSTANT PLS_INTEGER := -20001;
  c_err_dados_invalidos       CONSTANT PLS_INTEGER := -20002;
  c_err_saldo_insuficiente    CONSTANT PLS_INTEGER := -20003;
  c_err_chave_conflito        CONSTANT PLS_INTEGER := -20004;
  c_err_extrato_imutavel      CONSTANT PLS_INTEGER := -20005;
  c_err_carteira_ocupada      CONSTANT PLS_INTEGER := -20006;

  -- Cria uma carteira com saldo 0.00. Titular e opcional (ate 120 caracteres).
  PROCEDURE CRIAR_CARTEIRA(
    p_titular      IN  VARCHAR2,
    p_carteira_id  OUT NUMBER,
    p_saldo        OUT NUMBER,
    p_criada_em    OUT TIMESTAMP WITH TIME ZONE
  );

  -- Registra um credito ou debito de forma idempotente.
  -- p_repetida = 1 quando a chave ja tinha sido aplicada com o mesmo tipo e valor:
  -- nesse caso nada muda e os OUT devolvem o resultado da primeira chamada.
  PROCEDURE REGISTRAR_OPERACAO(
    p_carteira_id    IN  NUMBER,
    p_tipo           IN  VARCHAR2,
    p_valor          IN  NUMBER,
    p_chave          IN  VARCHAR2,
    p_lancamento_id  OUT NUMBER,
    p_saldo_apos     OUT NUMBER,
    p_criado_em      OUT TIMESTAMP WITH TIME ZONE,
    p_repetida       OUT NUMBER
  );

END PKG_WALLET;
/

CREATE OR REPLACE PACKAGE BODY PKG_WALLET AS

  -- Maior valor que cabe em NUMBER(15,2).
  c_valor_maximo  CONSTANT NUMBER := 9999999999999.99;

  -- ORA-30006: tempo esgotado esperando o lock da linha (FOR UPDATE WAIT).
  e_recurso_ocupado EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_recurso_ocupado, -30006);

  PROCEDURE CRIAR_CARTEIRA(
    p_titular      IN  VARCHAR2,
    p_carteira_id  OUT NUMBER,
    p_saldo        OUT NUMBER,
    p_criada_em    OUT TIMESTAMP WITH TIME ZONE
  ) IS
    -- No Oracle, string vazia e NULL. TRIM normaliza "   " para NULL tambem.
    v_titular VARCHAR2(4000) := TRIM(p_titular);
  BEGIN
    IF LENGTH(v_titular) > 120 THEN
      RAISE_APPLICATION_ERROR(c_err_dados_invalidos, 'Titular excede 120 caracteres.');
    END IF;

    INSERT INTO CARTEIRA (ID, TITULAR)
    VALUES (SEQ_CARTEIRA_ID.NEXTVAL, v_titular)
    RETURNING ID, SALDO, CRIADA_EM INTO p_carteira_id, p_saldo, p_criada_em;
  END CRIAR_CARTEIRA;

  PROCEDURE REGISTRAR_OPERACAO(
    p_carteira_id    IN  NUMBER,
    p_tipo           IN  VARCHAR2,
    p_valor          IN  NUMBER,
    p_chave          IN  VARCHAR2,
    p_lancamento_id  OUT NUMBER,
    p_saldo_apos     OUT NUMBER,
    p_criado_em      OUT TIMESTAMP WITH TIME ZONE,
    p_repetida       OUT NUMBER
  ) IS
    v_saldo          CARTEIRA.SALDO%TYPE;
    v_novo_saldo     CARTEIRA.SALDO%TYPE;
    v_lancamento_id  LANCAMENTO.ID%TYPE;
    v_ex_tipo        LANCAMENTO.TIPO%TYPE;
    v_ex_valor       LANCAMENTO.VALOR%TYPE;
  BEGIN
    p_repetida := 0;

    -- 1. Validacao (antes de gastar lock).
    IF p_carteira_id IS NULL THEN
      RAISE_APPLICATION_ERROR(c_err_dados_invalidos, 'Carteira e obrigatoria.');
    END IF;
    IF p_tipo IS NULL OR p_tipo NOT IN ('CREDITO', 'DEBITO') THEN
      RAISE_APPLICATION_ERROR(c_err_dados_invalidos, 'Tipo deve ser CREDITO ou DEBITO.');
    END IF;
    IF p_valor IS NULL OR p_valor <= 0 THEN
      RAISE_APPLICATION_ERROR(c_err_dados_invalidos, 'Valor deve ser maior que zero.');
    END IF;
    -- NUMBER(15,2) arredonda em silencio (10.005 viraria 10.01): rejeitamos antes.
    IF p_valor <> ROUND(p_valor, 2) THEN
      RAISE_APPLICATION_ERROR(c_err_dados_invalidos, 'Valor aceita no maximo 2 casas decimais.');
    END IF;
    IF p_valor > c_valor_maximo THEN
      RAISE_APPLICATION_ERROR(c_err_dados_invalidos, 'Valor acima do maximo permitido.');
    END IF;
    IF p_chave IS NULL OR TRIM(p_chave) IS NULL THEN
      RAISE_APPLICATION_ERROR(c_err_dados_invalidos, 'Chave de idempotencia e obrigatoria.');
    END IF;
    IF LENGTH(p_chave) > 100 THEN
      RAISE_APPLICATION_ERROR(c_err_dados_invalidos, 'Chave de idempotencia excede 100 caracteres.');
    END IF;

    -- 2. Trava a linha da carteira (lock pessimista). Operacoes na mesma carteira ficam em fila;
    --    carteiras diferentes nao se bloqueiam. WAIT 5: espera ate 5 s em vez de ficar preso para sempre.
    --    O lock e liberado no COMMIT/ROLLBACK de quem chamou.
    BEGIN
      SELECT SALDO INTO v_saldo
        FROM CARTEIRA
       WHERE ID = p_carteira_id
         FOR UPDATE WAIT 5;
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        RAISE_APPLICATION_ERROR(c_err_carteira_inexistente, 'Carteira nao encontrada.');
      WHEN e_recurso_ocupado THEN
        RAISE_APPLICATION_ERROR(c_err_carteira_ocupada, 'Carteira ocupada. Tente novamente.');
    END;

    -- 3. Idempotencia. Feita DEPOIS do lock de proposito: como esta consulta roda depois de
    --    obtermos o lock, ela enxerga o que a transacao anterior (que segurava o lock) ja confirmou.
    --    Assim duas chamadas iguais simultaneas nunca passam as duas por aqui como "novas".
    BEGIN
      SELECT ID, TIPO, VALOR, SALDO_APOS, CRIADO_EM
        INTO v_lancamento_id, v_ex_tipo, v_ex_valor, p_saldo_apos, p_criado_em
        FROM LANCAMENTO
       WHERE CARTEIRA_ID = p_carteira_id
         AND CHAVE_IDEMPOTENCIA = p_chave;

      IF v_ex_tipo = p_tipo AND v_ex_valor = p_valor THEN
        p_lancamento_id := v_lancamento_id;
        p_repetida := 1;
        RETURN;
      END IF;
      RAISE_APPLICATION_ERROR(c_err_chave_conflito,
                              'Chave de idempotencia ja usada em outra operacao.');
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        NULL; -- chave nova: segue o fluxo normal
    END;

    -- 4. Regra de saldo.
    IF p_tipo = 'DEBITO' THEN
      IF v_saldo < p_valor THEN
        RAISE_APPLICATION_ERROR(c_err_saldo_insuficiente, 'Saldo insuficiente.');
      END IF;
      v_novo_saldo := v_saldo - p_valor;
    ELSE
      v_novo_saldo := v_saldo + p_valor;
      IF v_novo_saldo > c_valor_maximo THEN
        RAISE_APPLICATION_ERROR(c_err_dados_invalidos, 'Saldo excederia o limite permitido.');
      END IF;
    END IF;

    -- 5. Grava o lancamento e atualiza o saldo na mesma transacao (atomico).
    --    O contexto diz a trigger de auditoria qual operacao esta alterando o saldo.
    v_lancamento_id := SEQ_LANCAMENTO_ID.NEXTVAL;

    INSERT INTO LANCAMENTO (ID, CARTEIRA_ID, TIPO, VALOR, SALDO_APOS, CHAVE_IDEMPOTENCIA)
    VALUES (v_lancamento_id, p_carteira_id, p_tipo, p_valor, v_novo_saldo, p_chave)
    RETURNING CRIADO_EM INTO p_criado_em;

    PKG_WALLET_CTX.DEFINIR_OPERACAO(v_lancamento_id);
    UPDATE CARTEIRA SET SALDO = v_novo_saldo WHERE ID = p_carteira_id;
    PKG_WALLET_CTX.LIMPAR_OPERACAO;

    p_lancamento_id := v_lancamento_id;
    p_saldo_apos    := v_novo_saldo;

  EXCEPTION
    WHEN OTHERS THEN
      -- Nao deixa o contexto "sujo" na sessao (importante com pool de conexoes). Relanca o erro original.
      PKG_WALLET_CTX.LIMPAR_OPERACAO;
      RAISE;
  END REGISTRAR_OPERACAO;

END PKG_WALLET;
/
