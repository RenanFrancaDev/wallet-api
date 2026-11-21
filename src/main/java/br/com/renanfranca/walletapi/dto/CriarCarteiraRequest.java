package br.com.renanfranca.walletapi.dto;

import jakarta.validation.constraints.Size;

/**
 * Corpo de POST /wallets. O titular e opcional.
 *
 * <p>{@code @Size} e o Bean Validation (o Zod do Java): rejeita o corpo antes de chegar ao servico.
 * A regra definitiva continua no PKG_WALLET; esta validacao so evita uma viagem ao banco.
 */
public record CriarCarteiraRequest(@Size(max = 120, message = "deve ter no máximo 120 caracteres") String titular) {
}
