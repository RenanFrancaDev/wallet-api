package br.com.renanfranca.walletapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;

/** O contrato OpenAPI declara a API key, para o Swagger UI mandar o header em todas as chamadas. */
class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();
    private final OpenAPI openApi = config.openApi();

    @Test
    void identificaAApi() {
        assertThat(openApi.getInfo().getTitle()).isEqualTo("Wallet API");
        assertThat(openApi.getInfo().getVersion()).isNotBlank();
        assertThat(openApi.getInfo().getDescription()).contains("Oracle");
    }

    @Test
    void declaraAApiKeyNoHeaderXApiKey() {
        SecurityScheme esquema =
                openApi.getComponents().getSecuritySchemes().get(OpenApiConfig.ESQUEMA_API_KEY);

        assertThat(esquema.getType()).isEqualTo(SecurityScheme.Type.APIKEY);
        assertThat(esquema.getIn()).isEqualTo(SecurityScheme.In.HEADER);
        assertThat(esquema.getName()).isEqualTo("X-API-Key");
    }

    @Test
    void exigeAApiKeyEmTodasAsOperacoes() {
        assertThat(openApi.getSecurity())
                .singleElement()
                .satisfies(exigencia ->
                        assertThat(exigencia).containsKey(OpenApiConfig.ESQUEMA_API_KEY));
    }

    @Test
    void registraOCustomizerDasRespostasDeErro() {
        assertThat(config.respostasDeErro()).isInstanceOf(RespostasDeErroCustomizer.class);
    }
}
