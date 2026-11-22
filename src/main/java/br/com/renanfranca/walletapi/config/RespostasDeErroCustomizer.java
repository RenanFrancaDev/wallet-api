package br.com.renanfranca.walletapi.config;

import br.com.renanfranca.walletapi.exception.ErroResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * Completa a documentação gerada, para não repetir a mesma anotação em cada rota:
 *
 * <ul>
 *   <li>toda operação ganha a resposta {@code 401} (o filtro de API key vale para todas as rotas);
 *   <li>toda resposta de erro (4xx e 5xx) passa a apontar para o formato padrão de erro ({@link
 *       ErroResponse}), que é o mesmo em toda a API.
 * </ul>
 */
public class RespostasDeErroCustomizer implements OpenApiCustomizer {

    private static final String NOME_DO_ESQUEMA = ErroResponse.class.getSimpleName();

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        garantirEsquemaDeErro(openApi);
        openApi.getPaths()
                .values()
                .forEach(item ->
                        item.readOperations().forEach(RespostasDeErroCustomizer::completar));
    }

    private static void completar(Operation operacao) {
        if (operacao.getResponses() == null) {
            operacao.setResponses(new ApiResponses());
        }
        operacao.getResponses()
                .computeIfAbsent(
                        "401",
                        codigo -> new ApiResponse().description("API key ausente ou inválida"));
        operacao.getResponses().forEach(RespostasDeErroCustomizer::apontarParaOFormatoDeErro);
    }

    private static void apontarParaOFormatoDeErro(String codigo, ApiResponse resposta) {
        boolean erro = codigo.startsWith("4") || codigo.startsWith("5");
        // SEMPRE substitui: o springdoc anexa o tipo de retorno do método (ex.: CarteiraResponse) a
        // toda
        // resposta anotada, inclusive às de erro, e documentaria um 404 devolvendo uma carteira.
        if (erro) {
            Schema<?> referencia = new Schema<>().$ref("#/components/schemas/" + NOME_DO_ESQUEMA);
            resposta.setContent(
                    new Content()
                            .addMediaType("application/json", new MediaType().schema(referencia)));
        }
    }

    /**
     * O esquema só existe se algum controller o referenciou; aqui ele é registrado de qualquer
     * forma.
     */
    @SuppressWarnings(
            "rawtypes") // ModelConverters.readAll devolve Map<String, Schema> sem o tipo genérico
    private static void garantirEsquemaDeErro(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        Map<String, Schema> lidos = ModelConverters.getInstance().readAll(ErroResponse.class);
        lidos.forEach((nome, esquema) -> {
            if (openApi.getComponents().getSchemas() == null
                    || !openApi.getComponents().getSchemas().containsKey(nome)) {
                openApi.getComponents().addSchemas(nome, esquema);
            }
        });
    }
}
