package br.com.renanfranca.walletapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Completa o contrato gerado: 401 em toda operação e o formato padrão de erro em toda resposta de
 * erro. O caso central é o que o springdoc faz de errado sozinho: anexar o tipo de retorno do
 * método (um 200) a uma resposta de erro.
 */
class RespostasDeErroCustomizerTest {

    private static final String CARTEIRA = "#/components/schemas/CarteiraResponse";
    private static final String ERRO = "#/components/schemas/ErroResponse";

    private final RespostasDeErroCustomizer customizer = new RespostasDeErroCustomizer();

    private static ApiResponse comCorpo(String descricao, String referencia) {
        Schema<?> esquema = new Schema<>().$ref(referencia);
        return new ApiResponse()
                .description(descricao)
                .content(
                        new Content()
                                .addMediaType("application/json", new MediaType().schema(esquema)));
    }

    private static String referenciaDe(ApiResponse resposta) {
        return resposta.getContent().get("application/json").getSchema().get$ref();
    }

    private static OpenAPI contratoComUmaOperacao(ApiResponses respostas) {
        Operation operacao = new Operation().responses(respostas);
        return new OpenAPI()
                .components(new Components())
                .paths(new Paths().addPathItem("/x", new PathItem().get(operacao)));
    }

    private static ApiResponses respostasDeOperacao(OpenAPI openApi) {
        return openApi.getPaths().get("/x").getGet().getResponses();
    }

    @Test
    void acrescentaA401ATodaOperacao() {
        OpenAPI openApi =
                contratoComUmaOperacao(
                        new ApiResponses().addApiResponse("200", comCorpo("ok", CARTEIRA)));

        customizer.customise(openApi);

        ApiResponse naoAutorizado = respostasDeOperacao(openApi).get("401");
        assertThat(naoAutorizado.getDescription()).contains("API key");
        assertThat(referenciaDe(naoAutorizado)).isEqualTo(ERRO);
    }

    @Test
    void substituiOCorpoDeSucessoAnexadoPorEnganoAsRespostasDeErro() {
        // É o que o springdoc faz: um 404 anotado sem corpo herda o tipo de retorno
        // (CarteiraResponse).
        OpenAPI openApi =
                contratoComUmaOperacao(
                        new ApiResponses()
                                .addApiResponse("200", comCorpo("ok", CARTEIRA))
                                .addApiResponse("404", comCorpo("não encontrada", CARTEIRA))
                                .addApiResponse("422", comCorpo("saldo insuficiente", CARTEIRA))
                                .addApiResponse("503", comCorpo("ocupada", CARTEIRA)));

        customizer.customise(openApi);

        ApiResponses respostas = respostasDeOperacao(openApi);
        assertThat(referenciaDe(respostas.get("404"))).isEqualTo(ERRO);
        assertThat(referenciaDe(respostas.get("422"))).isEqualTo(ERRO);
        assertThat(referenciaDe(respostas.get("503"))).isEqualTo(ERRO);
        assertThat(respostas.get("404").getDescription()).isEqualTo("não encontrada");
    }

    @Test
    void apontaParaOFormatoDeErroMesmoSemCorpoAnterior() {
        OpenAPI openApi =
                contratoComUmaOperacao(
                        new ApiResponses()
                                .addApiResponse("409", new ApiResponse().description("conflito")));

        customizer.customise(openApi);

        assertThat(referenciaDe(respostasDeOperacao(openApi).get("409"))).isEqualTo(ERRO);
    }

    @Test
    void naoMexeNasRespostasDeSucesso() {
        OpenAPI openApi =
                contratoComUmaOperacao(
                        new ApiResponses()
                                .addApiResponse("200", comCorpo("ok", CARTEIRA))
                                .addApiResponse("201", comCorpo("criada", CARTEIRA)));

        customizer.customise(openApi);

        assertThat(referenciaDe(respostasDeOperacao(openApi).get("200"))).isEqualTo(CARTEIRA);
        assertThat(referenciaDe(respostasDeOperacao(openApi).get("201"))).isEqualTo(CARTEIRA);
    }

    @Test
    void preservaADescricaoDeUma401JaDocumentada() {
        OpenAPI openApi =
                contratoComUmaOperacao(
                        new ApiResponses()
                                .addApiResponse(
                                        "401", new ApiResponse().description("minha descrição")));

        customizer.customise(openApi);

        assertThat(respostasDeOperacao(openApi).get("401").getDescription())
                .isEqualTo("minha descrição");
    }

    @Test
    void registraOEsquemaDoFormatoDeErroNoContrato() {
        OpenAPI openApi = contratoComUmaOperacao(new ApiResponses());

        customizer.customise(openApi);

        assertThat(openApi.getComponents().getSchemas()).containsKey("ErroResponse");
        // A API do swagger devolve o Schema "bruto" (sem tipo genérico), e o modo estrito do
        // compilador
        // (-Xlint:all) recusa usar isso direto. Map<?, ?> aceita o tipo bruto sem aviso, e converto
        // só os nomes.
        Map<?, ?> propriedades =
                openApi.getComponents().getSchemas().get("ErroResponse").getProperties();
        List<String> nomes = propriedades.keySet().stream().map(String::valueOf).toList();
        assertThat(nomes)
                .containsExactlyInAnyOrder("codigo", "mensagem", "timestamp", "path", "detalhes");
    }

    @Test
    void operacaoSemNenhumaRespostaGanhaA401() {
        Operation semRespostas = new Operation();
        OpenAPI openApi =
                new OpenAPI()
                        .components(new Components())
                        .paths(new Paths().addPathItem("/x", new PathItem().get(semRespostas)));

        customizer.customise(openApi);

        assertThat(semRespostas.getResponses()).containsKey("401");
    }

    @Test
    void contratoSemCaminhosNaoFazNada() {
        assertThatCode(() -> customizer.customise(new OpenAPI())).doesNotThrowAnyException();
    }
}
