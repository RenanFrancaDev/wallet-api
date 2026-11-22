package br.com.renanfranca.walletapi.controller;

import br.com.renanfranca.walletapi.dto.CarteiraResponse;
import br.com.renanfranca.walletapi.dto.CriarCarteiraRequest;
import br.com.renanfranca.walletapi.model.Carteira;
import br.com.renanfranca.walletapi.service.CarteiraService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Endpoints de carteira. Analogia Node: um Router do Express; cada metodo e uma rota.
 *
 * <p>O controller so traduz HTTP para chamadas de servico e de volta. Nao tem regra de negocio. As
 * anotacoes do OpenAPI ({@code @Operation}, {@code @ApiResponse}) so alimentam a documentacao em
 * /swagger-ui.html.
 */
@Tag(name = "Carteiras", description = "Criar uma carteira e consultar o saldo")
@RestController
@RequestMapping("/wallets")
public class CarteiraController {

    private final CarteiraService service;

    public CarteiraController(CarteiraService service) {
        this.service = service;
    }

    /**
     * Cria uma carteira com saldo 0.00. Corpo opcional: {"titular": "Maria"}. Devolve 201 e o
     * header Location.
     */
    @Operation(
            summary = "Cria uma carteira",
            description =
                    "Cria uma carteira com saldo 0.00. O corpo é opcional (o titular é opcional, até 120 caracteres).")
    @ApiResponse(
            responseCode = "201",
            description = "Carteira criada. O header Location aponta para ela.",
            content = @Content(schema = @Schema(implementation = CarteiraResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Dados inválidos (titular com mais de 120 caracteres ou JSON malformado)")
    @PostMapping
    public ResponseEntity<CarteiraResponse> criar(
            @Valid @RequestBody(required = false) CriarCarteiraRequest corpo) {
        String titular = corpo == null ? null : corpo.titular();
        Carteira carteira = service.criar(titular);
        URI location =
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(carteira.id())
                        .toUri();
        return ResponseEntity.created(location).body(CarteiraResponse.de(carteira));
    }

    /** Consulta a carteira e o saldo. 404 se nao existir. */
    @Operation(summary = "Consulta a carteira e o saldo")
    @ApiResponse(
            responseCode = "200",
            description = "A carteira, com o saldo atual (sempre com 2 casas decimais).",
            content = @Content(schema = @Schema(implementation = CarteiraResponse.class)))
    @ApiResponse(responseCode = "400", description = "Identificador não numérico")
    @ApiResponse(responseCode = "404", description = "Carteira não encontrada")
    @GetMapping("/{id}")
    public CarteiraResponse buscar(@PathVariable long id) {
        return CarteiraResponse.de(service.buscar(id));
    }
}
