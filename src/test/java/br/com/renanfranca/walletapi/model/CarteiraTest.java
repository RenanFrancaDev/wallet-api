package br.com.renanfranca.walletapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** O saldo sempre sai com 2 casas decimais, venha como o Oracle o devolver. */
class CarteiraTest {

    private static Carteira comSaldo(String saldo) {
        return new Carteira(1, "Maria", new BigDecimal(saldo), OffsetDateTime.now());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "0, 0.00",
        "100.5, 100.50",
        "7, 7.00",
        "10.25, 10.25",
        "9999999999999.99, 9999999999999.99"
    })
    void normalizaOSaldoParaDuasCasas(String vindoDoBanco, String esperado) {
        assertThat(comSaldo(vindoDoBanco).saldo().toPlainString()).isEqualTo(esperado);
    }

    @Test
    void saldoComMaisDeDuasCasasEBugELancaExcecao() {
        assertThatThrownBy(() -> comSaldo("1.005")).isInstanceOf(ArithmeticException.class);
    }
}
