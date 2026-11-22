package br.com.renanfranca.walletapi.dto;

import br.com.renanfranca.walletapi.model.TipoOperacao;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Corpo de POST /wallets/{id}/transactions. A chave de idempotência NÃO vai aqui: vai no header
 * {@code Idempotency-Key}, o padrão de mercado, porque ela identifica a tentativa e não faz parte
 * do conteúdo da operação.
 *
 * <p>As validações evitam uma viagem ao banco; a regra definitiva continua no PKG_WALLET (que
 * devolve o mesmo código de erro, DADOS_INVALIDOS).
 */
public record RegistrarOperacaoRequest(
        @NotNull(message = "é obrigatório (CREDITO ou DEBITO)") TipoOperacao tipo,
        @NotNull(message = "é obrigatório")
                @Positive(message = "deve ser maior que zero")
                @Digits(
                        integer = 13,
                        fraction = 2,
                        message = "deve ter no máximo 13 dígitos inteiros e 2 casas decimais")
                BigDecimal valor) {}
