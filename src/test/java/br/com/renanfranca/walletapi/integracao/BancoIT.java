package br.com.renanfranca.walletapi.integracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/**
 * As garantias do banco, provadas contra um Oracle de verdade: migrations, privilégio mínimo,
 * imutabilidade do extrato e trilha de auditoria. É o que antes se verificava à mão com {@code
 * sqlplus}, agora automático e rodando no CI.
 */
class BancoIT extends OracleIntegracaoBase {

    /**
     * Cria uma carteira com um crédito de 100 pelo PKG_WALLET, como a aplicação faria, e devolve o
     * id.
     */
    private static long carteiraComCredito100(String chave) throws SQLException {
        try (Connection app = comoApp()) {
            executar(
                    app,
                    """
                    DECLARE
                      c NUMBER; s NUMBER; t TIMESTAMP WITH TIME ZONE;
                      l NUMBER; a NUMBER; e TIMESTAMP WITH TIME ZONE; r NUMBER;
                    BEGIN
                      WALLET_OWNER.PKG_WALLET.CRIAR_CARTEIRA('integracao', c, s, t);
                      WALLET_OWNER.PKG_WALLET.REGISTRAR_OPERACAO(c, 'CREDITO', 100, '%s', l, a, e, r);
                      COMMIT;
                    END;\
                    """
                            .formatted(chave));
            return numero(app, "SELECT MAX(ID) FROM WALLET_OWNER.V_CARTEIRA");
        }
    }

    private static void esperaErroOracle(Connection conexao, String sql, int codigoEsperado) {
        assertThatThrownBy(() -> executar(conexao, sql))
                .isInstanceOfSatisfying(
                        SQLException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .as("ORA-%05d esperado para: %s", codigoEsperado, sql)
                                        .isEqualTo(codigoEsperado));
    }

    // ---------------------------------------------------------------- migrations

    @Test
    void asMigrationsDosDoisSchemasForamAplicadasComSucesso() throws SQLException {
        String sql =
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" IS NOT NULL AND \"success\" = 1";
        try (Connection owner = comoOwner();
                Connection audit = comoAudit()) {
            assertThat(numero(owner, sql)).as("migrations do owner").isEqualTo(6);
            assertThat(numero(audit, sql)).as("migrations do audit").isEqualTo(2);
        }
    }

    @Test
    void nenhumObjetoDoBancoFicouInvalido() throws SQLException {
        String sql = "SELECT COUNT(*) FROM USER_OBJECTS WHERE STATUS <> 'VALID'";
        try (Connection owner = comoOwner();
                Connection audit = comoAudit()) {
            assertThat(numero(owner, sql)).as("objetos inválidos do owner").isZero();
            assertThat(numero(audit, sql)).as("objetos inválidos do audit").isZero();
        }
    }

    // ---------------------------------------------------------------- privilégio mínimo

    @Test
    void oUsuarioDaAplicacaoNaoTemDmlNemLeituraDasTabelas() throws SQLException {
        try (Connection app = comoApp()) {
            // ORA-00942: "table or view does not exist" (o APP nem enxerga as tabelas).
            esperaErroOracle(app, "INSERT INTO WALLET_OWNER.CARTEIRA (ID) VALUES (999999)", 942);
            esperaErroOracle(app, "UPDATE WALLET_OWNER.CARTEIRA SET SALDO = 999999", 942);
            esperaErroOracle(app, "DELETE FROM WALLET_OWNER.LANCAMENTO", 942);
            esperaErroOracle(app, "SELECT COUNT(*) FROM WALLET_OWNER.CARTEIRA", 942);
        }
    }

    @Test
    void oUsuarioDaAplicacaoNaoAcessaAAuditoria() throws SQLException {
        try (Connection app = comoApp()) {
            esperaErroOracle(app, "SELECT COUNT(*) FROM WALLET_AUDIT.SALDO_AUDITORIA", 942);
            esperaErroOracle(app, "INSERT INTO WALLET_AUDIT.SALDO_AUDITORIA (ID) VALUES (1)", 942);
            // PLS-00201 (ORA-06550): nem a procedure de auditoria ele enxerga.
            esperaErroOracle(
                    app, "BEGIN WALLET_AUDIT.PKG_AUDIT.REGISTRAR_SALDO(1, 0, 0, NULL); END;", 6550);
        }
    }

    @Test
    void oUsuarioDaAplicacaoPodeLerAsViewsEExecutarOPackage() throws SQLException {
        long carteira = carteiraComCredito100("k-privilegio");
        try (Connection app = comoApp()) {
            assertThat(
                            numero(
                                    app,
                                    "SELECT SALDO FROM WALLET_OWNER.V_CARTEIRA WHERE ID = "
                                            + carteira))
                    .isEqualTo(100);
            assertThat(
                            numero(
                                    app,
                                    "SELECT COUNT(*) FROM WALLET_OWNER.V_EXTRATO WHERE CARTEIRA_ID = "
                                            + carteira))
                    .isEqualTo(1);
        }
    }

    @Test
    void ateODonoNaoEscreveDiretoNaTrilhaDeAuditoria() throws SQLException {
        // A opção B de auditoria: o OWNER só tem EXECUTE na procedure, nunca INSERT na tabela.
        try (Connection owner = comoOwner()) {
            esperaErroOracle(
                    owner,
                    "INSERT INTO WALLET_AUDIT.SALDO_AUDITORIA"
                            + " (ID, CARTEIRA_ID, SALDO_ANTERIOR, SALDO_NOVO, USUARIO_BANCO)"
                            + " VALUES (1, 1, 0, 0, 'forjado')",
                    942);
        }
    }

    // ---------------------------------------------------------------- imutabilidade e saldo

    @Test
    void oExtratoEImutavelAteParaOOwnerPorSqlDireto() throws SQLException {
        carteiraComCredito100("k-imutavel");
        try (Connection owner = comoOwner()) {
            // ORA-20005: lançado pela trigger TRG_LANCAMENTO_IMUTAVEL.
            esperaErroOracle(owner, "UPDATE LANCAMENTO SET VALOR = 999", 20005);
            esperaErroOracle(owner, "DELETE FROM LANCAMENTO", 20005);
        }
    }

    @Test
    void oBancoRecusaSaldoNegativoMesmoPorSqlDireto() throws SQLException {
        carteiraComCredito100("k-negativo");
        try (Connection owner = comoOwner()) {
            // ORA-02290: CHECK (SALDO >= 0), a última barreira.
            esperaErroOracle(owner, "UPDATE CARTEIRA SET SALDO = -1", 2290);
        }
    }

    // ---------------------------------------------------------------- trilha de auditoria

    @Test
    void aTrilhaRegistraAMudancaDeSaldoComAOperacaoEOUsuario() throws SQLException {
        long carteira = carteiraComCredito100("k-auditoria");

        try (Connection audit = comoAudit();
                PreparedStatement comando =
                        audit.prepareStatement(
                                "SELECT SALDO_ANTERIOR, SALDO_NOVO, OPERACAO_ID, USUARIO_BANCO FROM SALDO_AUDITORIA"
                                        + " WHERE CARTEIRA_ID = ? ORDER BY ID")) {
            comando.setLong(1, carteira);
            try (ResultSet linhas = comando.executeQuery()) {
                assertThat(linhas.next()).as("há uma linha na trilha").isTrue();
                assertThat(linhas.getLong("SALDO_ANTERIOR")).isZero();
                assertThat(linhas.getLong("SALDO_NOVO")).isEqualTo(100);
                assertThat(linhas.getObject("OPERACAO_ID"))
                        .as("operação que causou a mudança")
                        .isNotNull();
                assertThat(linhas.getString("USUARIO_BANCO")).isEqualTo("WALLET_APP");
                assertThat(linhas.next()).as("só uma mudança de saldo").isFalse();
            }
        }
    }

    @Test
    void saldoAlteradoPorSqlDiretoFicaNaTrilhaSemOperacaoEIssoSinalizaOProblema()
            throws SQLException {
        long carteira = carteiraComCredito100("k-direto");
        try (Connection owner = comoOwner()) {
            executar(owner, "UPDATE CARTEIRA SET SALDO = SALDO + 1 WHERE ID = " + carteira);
        }

        try (Connection audit = comoAudit()) {
            String sql =
                    "SELECT COUNT(*) FROM SALDO_AUDITORIA WHERE CARTEIRA_ID = "
                            + carteira
                            + " AND OPERACAO_ID IS NULL";
            assertThat(numero(audit, sql))
                    .as("a alteração fora do package aparece sem operação")
                    .isEqualTo(1);
        }
    }
}
