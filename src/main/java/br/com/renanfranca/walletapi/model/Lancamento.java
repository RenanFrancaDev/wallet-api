package br.com.renanfranca.walletapi.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/**
 * Lançamento do extrato (linha da view V_EXTRATO). Valor e saldo sempre com 2 casas decimais, como
 * em {@link Carteira}: o Oracle devolve {@code 100} e não {@code 100.00}.
 */
public record Lancamento(
        long id,
        long carteiraId,
        TipoOperacao tipo,
        BigDecimal valor,
        BigDecimal saldoApos,
        String chaveIdempotencia,
        OffsetDateTime criadoEm) {

    public Lancamento {
        valor = valor.setScale(2, RoundingMode.UNNECESSARY);
        saldoApos = saldoApos.setScale(2, RoundingMode.UNNECESSARY);
    }
}
