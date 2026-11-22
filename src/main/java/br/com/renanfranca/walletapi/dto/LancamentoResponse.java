package br.com.renanfranca.walletapi.dto;

import br.com.renanfranca.walletapi.model.Lancamento;
import br.com.renanfranca.walletapi.model.TipoOperacao;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Lançamento devolvido pela API. {@code saldoApos} é o saldo da carteira logo depois desta
 * operação.
 */
public record LancamentoResponse(
        long id,
        long carteiraId,
        TipoOperacao tipo,
        BigDecimal valor,
        BigDecimal saldoApos,
        String chaveIdempotencia,
        OffsetDateTime criadoEm) {

    public static LancamentoResponse de(Lancamento lancamento) {
        return new LancamentoResponse(
                lancamento.id(),
                lancamento.carteiraId(),
                lancamento.tipo(),
                lancamento.valor(),
                lancamento.saldoApos(),
                lancamento.chaveIdempotencia(),
                lancamento.criadoEm());
    }
}
