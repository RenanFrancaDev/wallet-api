package br.com.renanfranca.walletapi.integracao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * A verdade do banco, consultada DIRETO por JDBC (sem passar pela API), para os testes não
 * confiarem só no que a API diz sobre si mesma. As consultas ao extrato e ao saldo usam o dono; a
 * trilha, o usuário de auditoria.
 */
final class ConferenciaDoBanco {

    private ConferenciaDoBanco() {}

    static BigDecimal saldo(long carteira) throws SQLException {
        return decimal(
                OracleIntegracaoBase::comoOwner,
                "SELECT SALDO FROM CARTEIRA WHERE ID = ?",
                carteira);
    }

    /** Créditos menos débitos de todo o extrato da carteira. */
    static BigDecimal somaDoExtrato(long carteira) throws SQLException {
        return decimal(
                OracleIntegracaoBase::comoOwner,
                "SELECT NVL(SUM(CASE TIPO WHEN 'CREDITO' THEN VALOR ELSE -VALOR END), 0) FROM LANCAMENTO"
                        + " WHERE CARTEIRA_ID = ?",
                carteira);
    }

    static long lancamentos(long carteira) throws SQLException {
        return decimal(
                        OracleIntegracaoBase::comoOwner,
                        "SELECT COUNT(*) FROM LANCAMENTO WHERE CARTEIRA_ID = ?",
                        carteira)
                .longValue();
    }

    /** O SALDO_APOS do lançamento mais recente: tem de ser igual ao saldo da carteira. */
    static BigDecimal saldoDoUltimoLancamento(long carteira) throws SQLException {
        return decimal(
                OracleIntegracaoBase::comoOwner,
                "SELECT SALDO_APOS FROM (SELECT SALDO_APOS FROM LANCAMENTO WHERE CARTEIRA_ID = ?"
                        + " ORDER BY CRIADO_EM DESC, ID DESC) WHERE ROWNUM = 1",
                carteira);
    }

    static long linhasDeAuditoria(long carteira) throws SQLException {
        return decimal(
                        OracleIntegracaoBase::comoAudit,
                        "SELECT COUNT(*) FROM SALDO_AUDITORIA WHERE CARTEIRA_ID = ?",
                        carteira)
                .longValue();
    }

    /**
     * Quantas linhas da trilha NÃO encaixam na anterior: o saldo anterior de cada linha deve ser o
     * saldo novo da linha de antes. Zero prova que toda mudança de saldo foi registrada, na ordem,
     * sem buraco nem sobreposição.
     */
    static long quebrasNaCadeiaDaAuditoria(long carteira) throws SQLException {
        return decimal(
                        OracleIntegracaoBase::comoAudit,
                        "SELECT COUNT(*) FROM (SELECT SALDO_ANTERIOR, LAG(SALDO_NOVO) OVER (ORDER BY ID) AS ANTERIOR"
                                + " FROM SALDO_AUDITORIA WHERE CARTEIRA_ID = ?)"
                                + " WHERE ANTERIOR IS NOT NULL AND ANTERIOR <> SALDO_ANTERIOR",
                        carteira)
                .longValue();
    }

    @FunctionalInterface
    private interface Conexao {
        Connection abrir() throws SQLException;
    }

    private static BigDecimal decimal(Conexao conexao, String sql, long carteira)
            throws SQLException {
        try (Connection c = conexao.abrir();
                PreparedStatement comando = c.prepareStatement(sql)) {
            comando.setLong(1, carteira);
            try (ResultSet resultado = comando.executeQuery()) {
                resultado.next();
                return resultado.getBigDecimal(1);
            }
        }
    }
}
