package br.com.renanfranca.walletapi.controller;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import br.com.renanfranca.walletapi.dto.CarteiraResponse;
import br.com.renanfranca.walletapi.dto.CriarCarteiraRequest;
import br.com.renanfranca.walletapi.model.Carteira;
import br.com.renanfranca.walletapi.service.CarteiraService;

/**
 * Endpoints de carteira. Analogia Node: um Router do Express; cada metodo e uma rota.
 *
 * <p>O controller so traduz HTTP para chamadas de servico e de volta. Nao tem regra de negocio.
 */
@RestController
@RequestMapping("/wallets")
public class CarteiraController {

    private final CarteiraService service;

    public CarteiraController(CarteiraService service) {
        this.service = service;
    }

    /** Cria uma carteira com saldo 0.00. Corpo opcional: {"titular": "Maria"}. Devolve 201 e o header Location. */
    @PostMapping
    public ResponseEntity<CarteiraResponse> criar(@Valid @RequestBody(required = false) CriarCarteiraRequest corpo) {
        String titular = corpo == null ? null : corpo.titular();
        Carteira carteira = service.criar(titular);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(carteira.id())
                .toUri();
        return ResponseEntity.created(location).body(CarteiraResponse.de(carteira));
    }

    /** Consulta a carteira e o saldo. 404 se nao existir. */
    @GetMapping("/{id}")
    public CarteiraResponse buscar(@PathVariable long id) {
        return CarteiraResponse.de(service.buscar(id));
    }
}
