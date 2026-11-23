package br.com.renanfranca.walletapi.dto;

import br.com.renanfranca.walletapi.model.Carteira;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Corpo devolvido por POST /wallets e GET /wallets/{id}. */
public record CarteiraResponse(
        @Schema(description = "Identificador da carteira", example = "21") long id,
        @Schema(description = "Titular (opcional)", example = "Maria", nullable = true)
                String titular,
        @Schema(description = "Saldo em reais, sempre com 2 casas decimais", example = "100.00")
                BigDecimal saldo,
        @Schema(
                        description = "Quando a carteira foi criada (ISO-8601)",
                        example = "2026-11-22T11:54:20.618984Z")
                OffsetDateTime criadaEm) {

    public static CarteiraResponse de(Carteira carteira) {
        return new CarteiraResponse(
                carteira.id(), carteira.titular(), carteira.saldo(), carteira.criadaEm());
    }
}
