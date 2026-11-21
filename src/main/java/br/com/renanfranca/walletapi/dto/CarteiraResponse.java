package br.com.renanfranca.walletapi.dto;

import br.com.renanfranca.walletapi.model.Carteira;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Corpo devolvido por POST /wallets e GET /wallets/{id}. */
public record CarteiraResponse(long id, String titular, BigDecimal saldo, OffsetDateTime criadaEm) {

    public static CarteiraResponse de(Carteira carteira) {
        return new CarteiraResponse(
                carteira.id(), carteira.titular(), carteira.saldo(), carteira.criadaEm());
    }
}
