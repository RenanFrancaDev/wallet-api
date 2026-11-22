package br.com.renanfranca.walletapi.repository;

import br.com.renanfranca.walletapi.model.Lancamento;
import br.com.renanfranca.walletapi.model.OperacaoRegistrada;
import br.com.renanfranca.walletapi.model.TipoOperacao;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlOutParameter;
import org.springframework.jdbc.core.SqlParameter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.stereotype.Repository;

/**
 * Registro de operações. ESCRITA só pelo PKG_WALLET.REGISTRAR_OPERACAO (valida, trava a carteira,
 * trata a idempotência e atualiza o saldo, tudo no banco); LEITURA pela view V_EXTRATO. Não
 * controla transação: é o serviço.
 */
@Repository
public class LancamentoRepository {

    private static final String SCHEMA = "WALLET_OWNER";

    private static final String SELECT =
            "SELECT ID, CARTEIRA_ID, TIPO, VALOR, SALDO_APOS, CHAVE_IDEMPOTENCIA, CRIADO_EM FROM "
                    + SCHEMA
                    + ".V_EXTRATO";

    private final JdbcClient jdbc;
    private final SimpleJdbcCall registrarOperacao;

    public LancamentoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbc = JdbcClient.create(jdbcTemplate);
        // Parâmetros declarados na MESMA ORDEM da procedure (4 de entrada e 4 de saída).
        this.registrarOperacao =
                new SimpleJdbcCall(jdbcTemplate)
                        .withSchemaName(SCHEMA)
                        .withCatalogName("PKG_WALLET")
                        .withProcedureName("REGISTRAR_OPERACAO")
                        .withoutProcedureColumnMetaDataAccess()
                        .declareParameters(
                                new SqlParameter("p_carteira_id", Types.NUMERIC),
                                new SqlParameter("p_tipo", Types.VARCHAR),
                                new SqlParameter("p_valor", Types.NUMERIC),
                                new SqlParameter("p_chave", Types.VARCHAR),
                                new SqlOutParameter("p_lancamento_id", Types.NUMERIC),
                                new SqlOutParameter("p_saldo_apos", Types.NUMERIC),
                                new SqlOutParameter("p_criado_em", Types.TIMESTAMP),
                                new SqlOutParameter("p_repetida", Types.NUMERIC));
    }

    /** Registra a operação pelo package e devolve o lançamento (o original, se for repetição). */
    public OperacaoRegistrada registrar(
            long carteiraId, TipoOperacao tipo, BigDecimal valor, String chave) {
        Map<String, Object> saida =
                registrarOperacao.execute(
                        new MapSqlParameterSource()
                                .addValue("p_carteira_id", carteiraId)
                                .addValue("p_tipo", tipo.name())
                                .addValue("p_valor", valor)
                                .addValue("p_chave", chave));
        long lancamentoId = ((Number) saida.get("p_lancamento_id")).longValue();
        boolean repetida = ((Number) saida.get("p_repetida")).intValue() == 1;
        Lancamento lancamento =
                buscarPorId(lancamentoId)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Lançamento recém-registrado não encontrado: "
                                                + lancamentoId));
        return new OperacaoRegistrada(lancamento, repetida);
    }

    public Optional<Lancamento> buscarPorId(long id) {
        return jdbc.sql(SELECT + " WHERE ID = :id")
                .param("id", id)
                .query(LancamentoRepository::mapear)
                .optional();
    }

    /**
     * Extrato de uma carteira, do mais recente para o mais antigo, paginado. {@code de} é inclusivo
     * e {@code ate} é exclusivo (intervalo semiaberto); qualquer um pode ser nulo. A ordem usa o
     * índice IX_LANCAMENTO_EXTRATO (carteira, data, id).
     */
    public List<Lancamento> listar(
            long carteiraId,
            OffsetDateTime de,
            OffsetDateTime ate,
            long deslocamento,
            int tamanho) {
        String sql =
                SELECT
                        + filtro(de, ate)
                        + " ORDER BY CRIADO_EM DESC, ID DESC"
                        + " OFFSET :deslocamento ROWS FETCH NEXT :tamanho ROWS ONLY";
        return comFiltro(jdbc.sql(sql), carteiraId, de, ate)
                .param("deslocamento", deslocamento)
                .param("tamanho", tamanho)
                .query(LancamentoRepository::mapear)
                .list();
    }

    /**
     * Quantos lançamentos o extrato tem no período (para o cliente saber quantas páginas existem).
     */
    public long contar(long carteiraId, OffsetDateTime de, OffsetDateTime ate) {
        String sql = "SELECT COUNT(*) FROM " + SCHEMA + ".V_EXTRATO" + filtro(de, ate);
        return comFiltro(jdbc.sql(sql), carteiraId, de, ate).query(Long.class).single();
    }

    /**
     * Monta o WHERE só com os filtros informados, sem ligar parâmetro nulo (que costuma exigir o
     * tipo SQL explícito no driver da Oracle).
     */
    private static String filtro(OffsetDateTime de, OffsetDateTime ate) {
        StringBuilder where = new StringBuilder(" WHERE CARTEIRA_ID = :carteiraId");
        if (de != null) {
            where.append(" AND CRIADO_EM >= :de");
        }
        if (ate != null) {
            where.append(" AND CRIADO_EM < :ate");
        }
        return where.toString();
    }

    private static JdbcClient.StatementSpec comFiltro(
            JdbcClient.StatementSpec consulta,
            long carteiraId,
            OffsetDateTime de,
            OffsetDateTime ate) {
        JdbcClient.StatementSpec com = consulta.param("carteiraId", carteiraId);
        if (de != null) {
            com = com.param("de", de);
        }
        if (ate != null) {
            com = com.param("ate", ate);
        }
        return com;
    }

    private static Lancamento mapear(ResultSet rs, int linha) throws SQLException {
        return new Lancamento(
                rs.getLong("ID"),
                rs.getLong("CARTEIRA_ID"),
                TipoOperacao.valueOf(rs.getString("TIPO")),
                rs.getBigDecimal("VALOR"),
                rs.getBigDecimal("SALDO_APOS"),
                rs.getString("CHAVE_IDEMPOTENCIA"),
                rs.getObject("CRIADO_EM", OffsetDateTime.class));
    }
}
