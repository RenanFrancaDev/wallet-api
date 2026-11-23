package br.com.renanfranca.walletapi.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Catalogo de erros: codigo do Oracle para erro de negocio e status HTTP. */
class ErroNegocioTest {

    @ParameterizedTest(name = "ORA-{0} -> {1} -> HTTP {2}")
    @CsvSource({
        "20001, CARTEIRA_NAO_ENCONTRADA, 404",
        "20002, DADOS_INVALIDOS, 400",
        "20003, SALDO_INSUFICIENTE, 422",
        "20004, CHAVE_IDEMPOTENCIA_CONFLITO, 409",
        "20005, EXTRATO_IMUTAVEL, 409",
        "20006, CARTEIRA_OCUPADA, 503"
    })
    void traduzCodigoDoOracleParaErroEStatus(int codigo, ErroNegocio esperado, int statusEsperado) {
        assertThat(ErroNegocio.deCodigoOracle(codigo)).contains(esperado);
        assertThat(esperado.status().value()).isEqualTo(statusEsperado);
    }

    @Test
    void aceitaOCodigoNegativoDoPlsql() {
        // SQLCODE do PL/SQL e negativo (-20003); SQLException.getErrorCode() do JDBC e positivo
        // (20003).
        assertThat(ErroNegocio.deCodigoOracle(-20003)).contains(ErroNegocio.SALDO_INSUFICIENTE);
    }

    @ParameterizedTest(name = "codigo {0} nao e erro de negocio")
    @CsvSource({"942", "1", "20000", "20007", "20999"})
    void codigoForaDoCatalogoNaoEErroDeNegocio(int codigo) {
        assertThat(ErroNegocio.deCodigoOracle(codigo)).isEmpty();
    }

    @Test
    void todoErroTemMensagem() {
        for (ErroNegocio erro : ErroNegocio.values()) {
            assertThat(erro.mensagem()).isNotBlank();
        }
    }
}
