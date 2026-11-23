package br.com.renanfranca.walletapi.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Valor e saldo do lançamento sempre saem com 2 casas decimais, venham como o Oracle os devolver.
 */
class LancamentoTest {

    @ParameterizedTest(name = "valor {0} e saldo {1} -> {2} e {3}")
    @CsvSource({"100, 100, 100.00, 100.00", "0.5, 99.5, 0.50, 99.50", "10.25, 0, 10.25, 0.00"})
    void normalizaValorESaldoParaDuasCasas(
            String valor, String saldo, String valorEsperado, String saldoEsperado) {
        Lancamento lancamento =
                new Lancamento(
                        1,
                        1,
                        TipoOperacao.CREDITO,
                        new BigDecimal(valor),
                        new BigDecimal(saldo),
                        "k1",
                        OffsetDateTime.now());

        assertThat(lancamento.valor().toPlainString()).isEqualTo(valorEsperado);
        assertThat(lancamento.saldoApos().toPlainString()).isEqualTo(saldoEsperado);
    }
}
