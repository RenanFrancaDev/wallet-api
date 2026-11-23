package br.com.renanfranca.walletapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Corpo de POST /wallets. O titular e opcional.
 *
 * <p>{@code @Size} e o Bean Validation (o Zod do Java): rejeita o corpo antes de chegar ao servico.
 * A regra definitiva continua no PKG_WALLET; esta validacao so evita uma viagem ao banco.
 */
public record CriarCarteiraRequest(
        @Schema(
                        description = "Nome ou documento do titular (opcional, até 120 caracteres)",
                        example = "Maria")
                @Size(max = 120, message = "deve ter no máximo 120 caracteres")
                String titular) {}
