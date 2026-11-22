package br.com.renanfranca.walletapi.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.renanfranca.walletapi.integracao.ClienteHttp.Resposta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * O contrato OpenAPI gerado pela aplicação de verdade. É o teste que faltava para a documentação:
 * os testes unitários cobrem a configuração e o customizer, e este baixa o contrato que o springdoc
 * realmente gera.
 */
class OpenApiIT extends OracleIntegracaoBase {

    private static final String ERRO = "#/components/schemas/ErroResponse";

    private Resposta contrato;

    /**
     * Não pode ser um inicializador de campo: a porta só é injetada DEPOIS de o objeto ser
     * construído.
     */
    @BeforeEach
    void baixarOContrato() {
        contrato = clienteSemChave().get("/v3/api-docs");
    }

    @Test
    void oContratoEPublicoEDeclaraAsRotasEAApiKey() {
        assertThat(contrato.status()).isEqualTo(200);
        assertThat((String) contrato.json("$.openapi")).startsWith("3.");
        assertThat((String) contrato.json("$.info.title")).isEqualTo("Wallet API");
        assertThat((String) contrato.json("$.components.securitySchemes.ApiKeyAuth.name"))
                .isEqualTo("X-API-Key");
        assertThat((String) contrato.json("$.components.securitySchemes.ApiKeyAuth.in"))
                .isEqualTo("header");

        assertThat(contrato.corpo())
                .contains(
                        "\"/wallets\"",
                        "\"/wallets/{id}\"",
                        "\"/wallets/{carteiraId}/transactions\"");
    }

    @Test
    void todasAsRespostasDeErroApontamParaOFormatoPadrao() {
        // Os erros que o springdoc documentaria com o corpo de sucesso por engano (a regressão que
        // já nos pegou).
        assertThat((String) erro("/wallets/{id}", "get", "404")).isEqualTo(ERRO);
        assertThat((String) erro("/wallets/{carteiraId}/transactions", "post", "422"))
                .isEqualTo(ERRO);
        assertThat((String) erro("/wallets/{carteiraId}/transactions", "post", "409"))
                .isEqualTo(ERRO);
        assertThat((String) erro("/wallets/{carteiraId}/transactions", "post", "503"))
                .isEqualTo(ERRO);
        assertThat((String) erro("/wallets/{carteiraId}/transactions", "get", "400"))
                .isEqualTo(ERRO);
        // O 401 vale para toda operação.
        assertThat((String) erro("/wallets", "post", "401")).isEqualTo(ERRO);
    }

    @Test
    void contratoNaoDeclaraOTipoDeMidiaGenericoPoisAApiSoProduzJson() {
        // Sem "produces", o springdoc documenta as respostas como "*/*" (qualquer tipo), o que
        // engana o leitor.
        assertThat(contrato.corpo()).doesNotContain("\"*/*\"");
        assertThat(
                        (String)
                                contrato.json(
                                        "$.paths['/wallets/{id}'].get.responses['200']"
                                                + ".content['application/json'].schema['$ref']"))
                .isEqualTo("#/components/schemas/CarteiraResponse");
    }

    @Test
    void aRespostaDeSucessoDoRegistroDeOperacaoDocumentaOHeaderDeRepeticao() {
        assertThat(contrato.corpo()).contains("Idempotent-Replayed");
        assertThat(
                        (String)
                                contrato.json(
                                        "$.paths['/wallets/{carteiraId}/transactions'].post.responses['201']"
                                                + ".content['application/json'].schema['$ref']"))
                .isEqualTo("#/components/schemas/LancamentoResponse");
    }

    private <T> T erro(String caminho, String metodo, String codigo) {
        return contrato.json(
                "$.paths['"
                        + caminho
                        + "']."
                        + metodo
                        + ".responses['"
                        + codigo
                        + "']"
                        + ".content['application/json'].schema['$ref']");
    }
}
