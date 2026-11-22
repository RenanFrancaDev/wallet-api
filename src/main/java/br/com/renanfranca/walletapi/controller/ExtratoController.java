package br.com.renanfranca.walletapi.controller;

import br.com.renanfranca.walletapi.dto.LancamentoResponse;
import br.com.renanfranca.walletapi.dto.PaginaResponse;
import br.com.renanfranca.walletapi.service.ExtratoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Extrato de uma carteira. Mesma rota do registro de operação, outro método HTTP (GET). */
@Tag(
        name = "Extrato",
        description =
                "Consultar os lançamentos de uma carteira, com paginação e filtro por período")
@RestController
@RequestMapping("/wallets/{carteiraId}/transactions")
public class ExtratoController {

    private final ExtratoService service;

    public ExtratoController(ExtratoService service) {
        this.service = service;
    }

    /**
     * Lista os lançamentos, do mais recente para o mais antigo.
     *
     * @param from início do período (inclusivo), ISO-8601 com fuso, ex.: {@code
     *     2026-11-01T00:00:00Z}
     * @param to fim do período (exclusivo). Atenção: um {@code +} no fuso precisa ir codificado
     *     ({@code %2B})
     * @param page página, a partir de 0 (padrão 0)
     * @param size itens por página, de 1 a 100 (padrão 20)
     */
    @Operation(
            summary = "Extrato da carteira, paginado e com filtro por período",
            description =
                    "Do lançamento mais recente para o mais antigo (data e depois id). O período é o intervalo"
                            + " `[from, to)`: `from` inclusivo e `to` exclusivo. Só aparecem as operações aplicadas:"
                            + " uma repetição idempotente e um débito recusado não criam linha. Um `+` no fuso"
                            + " precisa ir codificado como `%2B` na URL.")
    @ApiResponse(
            responseCode = "200",
            description = "Uma página do extrato, com o total de itens e de páginas")
    @ApiResponse(
            responseCode = "400",
            description =
                    "Parâmetros inválidos (size fora de 1 a 100, page negativo, from depois de to, data mal formada)")
    @ApiResponse(responseCode = "404", description = "Carteira não encontrada")
    @GetMapping
    public PaginaResponse<LancamentoResponse> consultar(
            @PathVariable long carteiraId,
            @Parameter(
                            description = "Início do período (inclusivo), ISO-8601 com fuso.",
                            example = "2026-11-01T00:00:00Z")
                    @RequestParam(name = "from", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @Parameter(
                            description = "Fim do período (exclusivo), ISO-8601 com fuso.",
                            example = "2026-12-01T00:00:00Z")
                    @RequestParam(name = "to", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to,
            @Parameter(description = "Número da página, a partir de 0.", example = "0")
                    @RequestParam(name = "page", defaultValue = "0")
                    int page,
            @Parameter(description = "Itens por página, de 1 a 100.", example = "20")
                    @RequestParam(name = "size", defaultValue = "20")
                    int size) {
        return PaginaResponse.de(
                service.consultar(carteiraId, from, to, page, size), LancamentoResponse::de);
    }
}
