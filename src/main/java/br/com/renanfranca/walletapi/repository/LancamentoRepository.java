package br.com.renanfranca.walletapi.repository;

import br.com.renanfranca.walletapi.model.Lancamento;
import br.com.renanfranca.walletapi.model.OperacaoRegistrada;
import br.com.renanfranca.walletapi.model.TipoOperacao;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.OffsetDateTime;
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
        return jdbc.sql(
                        "SELECT ID, CARTEIRA_ID, TIPO, VALOR, SALDO_APOS, CHAVE_IDEMPOTENCIA, CRIADO_EM FROM "
                                + SCHEMA
                                + ".V_EXTRATO WHERE ID = :id")
                .param("id", id)
                .query((rs, linha) ->
                        new Lancamento(
                                rs.getLong("ID"),
                                rs.getLong("CARTEIRA_ID"),
                                TipoOperacao.valueOf(rs.getString("TIPO")),
                                rs.getBigDecimal("VALOR"),
                                rs.getBigDecimal("SALDO_APOS"),
                                rs.getString("CHAVE_IDEMPOTENCIA"),
                                rs.getObject("CRIADO_EM", OffsetDateTime.class)))
                .optional();
    }
}
