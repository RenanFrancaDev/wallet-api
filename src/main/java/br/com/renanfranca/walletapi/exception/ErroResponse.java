package br.com.renanfranca.walletapi.exception;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Payload padrao de erro de toda a API.
 *
 * @param codigo identificador estavel do erro, para o cliente tratar por codigo (ex.:
 *     SALDO_INSUFICIENTE)
 * @param mensagem texto legivel para humanos
 * @param timestamp momento em que o erro foi gerado
 * @param path caminho da requisicao que falhou
 * @param detalhes informacoes adicionais (ex.: campos invalidos); lista vazia quando nao ha
 */
@Schema(description = "Formato padrão de erro de toda a API")
public record ErroResponse(
        @Schema(
                        description =
                                "Identificador estável do erro, para o cliente tratar por código",
                        example = "SALDO_INSUFICIENTE")
                String codigo,
        @Schema(
                        description = "Texto legível para humanos",
                        example = "Saldo insuficiente para a operação.")
                String mensagem,
        @Schema(
                        description = "Momento em que o erro foi gerado",
                        example = "2026-11-22T08:54:21.393813-03:00")
                OffsetDateTime timestamp,
        @Schema(
                        description = "Caminho da requisição que falhou",
                        example = "/wallets/21/transactions")
                String path,
        @Schema(description = "Informações adicionais (campos inválidos etc.); vazia quando não há")
                List<String> detalhes) {}
