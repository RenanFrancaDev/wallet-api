package br.com.renanfranca.walletapi.controller;

import br.com.renanfranca.walletapi.dto.LancamentoResponse;
import br.com.renanfranca.walletapi.dto.PaginaResponse;
import br.com.renanfranca.walletapi.service.ExtratoService;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Extrato de uma carteira. Mesma rota do registro de operação, outro método HTTP (GET). */
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
    @GetMapping
    public PaginaResponse<LancamentoResponse> consultar(
            @PathVariable long carteiraId,
            @RequestParam(name = "from", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam(name = "to", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return PaginaResponse.de(
                service.consultar(carteiraId, from, to, page, size), LancamentoResponse::de);
    }
}
