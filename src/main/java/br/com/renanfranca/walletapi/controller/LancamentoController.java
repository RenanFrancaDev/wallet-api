package br.com.renanfranca.walletapi.controller;

import br.com.renanfranca.walletapi.dto.LancamentoResponse;
import br.com.renanfranca.walletapi.dto.RegistrarOperacaoRequest;
import br.com.renanfranca.walletapi.model.OperacaoRegistrada;
import br.com.renanfranca.walletapi.service.LancamentoService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
@RestController
@RequestMapping("/wallets/{carteiraId}/transactions")
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
    @PostMapping
    public ResponseEntity<LancamentoResponse> registrar(
            @PathVariable long carteiraId,
            @RequestHeader("Idempotency-Key") String chave,
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
