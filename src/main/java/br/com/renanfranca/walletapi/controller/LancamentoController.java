package br.com.renanfranca.walletapi.controller;

import br.com.renanfranca.walletapi.dto.LancamentoResponse;
import br.com.renanfranca.walletapi.dto.RegistrarOperacaoRequest;
import br.com.renanfranca.walletapi.model.OperacaoRegistrada;
import br.com.renanfranca.walletapi.service.LancamentoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de lançamentos (operações) de uma carteira. Só traduz HTTP; a regra de negócio está no
 * banco.
 */
@Tag(name = "Operações", description = "Registrar créditos e débitos, com idempotência")
@RestController
@RequestMapping(
        path = "/wallets/{carteiraId}/transactions",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class LancamentoController {

    /**
     * Header que marca uma repetição: a operação já tinha sido aplicada e este é o resultado
     * original.
     */
    static final String HEADER_REPETICAO = "Idempotent-Replayed";

    private final LancamentoService service;

    public LancamentoController(LancamentoService service) {
        this.service = service;
    }

    /**
     * Registra um crédito ou débito. Exige o header {@code Idempotency-Key}.
     *
     * <p>Sempre {@code 201} com o mesmo corpo: a repetição (mesma chave e mesmo conteúdo) devolve o
     * resultado da primeira chamada, sem alterar o saldo, e se distingue pelo header {@code
     * Idempotent-Replayed: true}. Mesma chave com conteúdo diferente é {@code 409}.
     */
    @Operation(
            summary = "Registra um crédito ou débito (idempotente)",
            description =
                    """
                    Aplica a operação ao saldo e a grava no extrato, de forma atômica e sob lock da carteira.

                    **Idempotência:** o header `Idempotency-Key` é obrigatório. Repetir a mesma chave com o mesmo \
                    conteúdo (retry, timeout) NÃO altera o saldo de novo: a resposta é a da primeira chamada \
                    (`201`, mesmo corpo) com o header `Idempotent-Replayed: true`. A mesma chave com conteúdo \
                    diferente é `409`.

                    **Saldo:** débito maior que o saldo é `422` e não altera nada. O valor aceita no máximo 2 casas \
                    decimais.\
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Operação aplicada (ou, numa repetição, o resultado original).",
            headers =
                    @Header(
                            name = HEADER_REPETICAO,
                            description =
                                    "`true` quando é uma repetição da mesma chave e do mesmo conteúdo.",
                            schema = @Schema(type = "boolean")),
            content = @Content(schema = @Schema(implementation = LancamentoResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "Dados inválidos: sem Idempotency-Key, tipo inválido, valor menor ou igual a zero"
                            + " ou com mais de 2 casas")
    @ApiResponse(responseCode = "404", description = "Carteira não encontrada")
    @ApiResponse(
            responseCode = "409",
            description =
                    "A chave de idempotência já foi usada em outra operação (conteúdo diferente)")
    @ApiResponse(responseCode = "422", description = "Saldo insuficiente para o débito")
    @ApiResponse(
            responseCode = "503",
            description =
                    "Carteira ocupada (lock não obtido a tempo); o cliente pode tentar de novo")
    @PostMapping
    public ResponseEntity<LancamentoResponse> registrar(
            @PathVariable long carteiraId,
            @Parameter(
                            description =
                                    "Identifica a tentativa. Reenvie a MESMA chave ao repetir uma"
                                            + " operação (até 100 caracteres).",
                            example = "pedido-123")
                    @RequestHeader("Idempotency-Key")
                    String chave,
            @Valid @RequestBody RegistrarOperacaoRequest corpo) {
        OperacaoRegistrada resultado =
                service.registrar(carteiraId, corpo.tipo(), corpo.valor(), chave);
        ResponseEntity.BodyBuilder resposta = ResponseEntity.status(HttpStatus.CREATED);
        if (resultado.repetida()) {
            resposta.header(HEADER_REPETICAO, "true");
        }
        return resposta.body(LancamentoResponse.de(resultado.lancamento()));
    }
}
