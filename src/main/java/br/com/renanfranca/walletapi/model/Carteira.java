package br.com.renanfranca.walletapi.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/**
 * Carteira como a aplicacao a enxerga (linha da view V_CARTEIRA).
 *
 * <p>{@code record}: classe imutavel com construtor, getters (id(), saldo()...), equals e toString
 * gerados pelo compilador. Equivale a um tipo/interface com campos readonly no TypeScript.
 *
 * <p>O saldo e BigDecimal (decimal exato), nunca double: dinheiro nao pode ter erro de ponto
 * flutuante. O construtor compacto abaixo normaliza o saldo para SEMPRE 2 casas decimais: o Oracle
 * devolve 0 (e nao 0.00) e 100.5 (e nao 100.50), e o contrato da API e "saldo em reais com 2
 * casas".
 */
public record Carteira(long id, String titular, BigDecimal saldo, OffsetDateTime criadaEm) {

    public Carteira {
        // UNNECESSARY: o banco so guarda 2 casas (NUMBER(15,2)); se vier mais, e bug e deve falhar
        // alto.
        saldo = saldo.setScale(2, RoundingMode.UNNECESSARY);
    }
}
