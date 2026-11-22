package br.com.renanfranca.walletapi.dto;

import br.com.renanfranca.walletapi.model.Lancamento;
import br.com.renanfranca.walletapi.model.TipoOperacao;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Lançamento devolvido pela API. {@code saldoApos} é o saldo da carteira logo depois desta
 * operação.
 */
public record LancamentoResponse(
        @Schema(description = "Identificador do lançamento", example = "22") long id,
        @Schema(description = "Carteira a que o lançamento pertence", example = "21")
                long carteiraId,
        @Schema(description = "Tipo da operação", example = "DEBITO") TipoOperacao tipo,
        @Schema(description = "Valor da operação, com 2 casas decimais", example = "30.00")
                BigDecimal valor,
        @Schema(description = "Saldo da carteira logo depois desta operação", example = "70.00")
                BigDecimal saldoApos,
        @Schema(description = "Chave de idempotência da operação", example = "pedido-123")
                String chaveIdempotencia,
        @Schema(
                        description = "Quando a operação foi aplicada (ISO-8601)",
                        example = "2026-11-22T11:54:21.605562Z")
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
