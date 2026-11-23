package br.com.renanfranca.walletapi.repository;

import br.com.renanfranca.walletapi.model.Carteira;
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
 * Acesso a dados da carteira.
 *
 * <p>ESCRITA: so pelo PKG_WALLET (o usuario da aplicacao nao tem DML em tabela). LEITURA: pela view
 * V_CARTEIRA.
 *
 * <p>Esta classe NAO controla transacao: quem abre e confirma e o servico (@Transactional).
 */
@Repository
public class CarteiraRepository {

    private static final String SCHEMA = "WALLET_OWNER";

    private final JdbcClient jdbc;
    private final SimpleJdbcCall criarCarteira;

    public CarteiraRepository(JdbcTemplate jdbcTemplate) {
        this.jdbc = JdbcClient.create(jdbcTemplate);
        // Chama WALLET_OWNER.PKG_WALLET.CRIAR_CARTEIRA(p_titular, OUT p_carteira_id, OUT p_saldo,
        // OUT p_criada_em).
        // Os parametros sao declarados na MESMA ORDEM da procedure; sem consultar o dicionario do
        // Oracle
        // (withoutProcedureColumnMetaDataAccess), porque a procedure tem um OUT de TIMESTAMP WITH
        // TIME ZONE.
        this.criarCarteira =
                new SimpleJdbcCall(jdbcTemplate)
                        .withSchemaName(SCHEMA)
                        .withCatalogName("PKG_WALLET")
                        .withProcedureName("CRIAR_CARTEIRA")
                        .withoutProcedureColumnMetaDataAccess()
                        .declareParameters(
                                new SqlParameter("p_titular", Types.VARCHAR),
                                new SqlOutParameter("p_carteira_id", Types.NUMERIC),
                                new SqlOutParameter("p_saldo", Types.NUMERIC),
                                new SqlOutParameter("p_criada_em", Types.TIMESTAMP));
    }

    /** Cria a carteira pelo package e devolve o id gerado (pela sequence, dentro do banco). */
    public long criar(String titular) {
        Map<String, Object> saida =
                criarCarteira.execute(new MapSqlParameterSource("p_titular", titular));
        return ((Number) saida.get("p_carteira_id")).longValue();
    }

    public Optional<Carteira> buscarPorId(long id) {
        return jdbc.sql(
                        "SELECT ID, TITULAR, SALDO, CRIADA_EM FROM "
                                + SCHEMA
                                + ".V_CARTEIRA WHERE ID = :id")
                .param("id", id)
                .query((rs, linha) ->
                        new Carteira(
                                rs.getLong("ID"),
                                rs.getString("TITULAR"),
                                rs.getBigDecimal("SALDO"),
                                rs.getObject("CRIADA_EM", OffsetDateTime.class)))
                .optional();
    }
}
