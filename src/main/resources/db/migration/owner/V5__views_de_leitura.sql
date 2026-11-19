-- Executada pelo Flyway conectado como WALLET_OWNER.
-- Views de leitura para a aplicacao. Escrever continua so pelo PKG_WALLET.
-- Expor view em vez da tabela limita colunas e permite mudar a tabela sem quebrar a leitura.

CREATE OR REPLACE VIEW V_CARTEIRA AS
SELECT ID, TITULAR, SALDO, CRIADA_EM
  FROM CARTEIRA;

CREATE OR REPLACE VIEW V_EXTRATO AS
SELECT ID, CARTEIRA_ID, TIPO, VALOR, SALDO_APOS, CHAVE_IDEMPOTENCIA, CRIADO_EM
  FROM LANCAMENTO;
