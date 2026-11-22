package br.com.renanfranca.walletapi.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentação da API (OpenAPI 3), gerada a partir do código e servida em {@code /v3/api-docs}; o
 * Swagger UI fica em {@code /swagger-ui.html}.
 *
 * <p>Analogia Node: é o {@code @nestjs/swagger} (o {@code DocumentBuilder}), só que aqui o
 * springdoc lê as anotações dos controllers e dos DTOs.
 */
@Configuration
public class OpenApiConfig {

    static final String ESQUEMA_API_KEY = "ApiKeyAuth";

    @Bean
    public OpenAPI openApi() {
        SecurityScheme apiKey =
                new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-API-Key")
                        .description(
                                "Chave da API (variável WALLET_API_KEY do .env). Obrigatória em todas as rotas,"
                                        + " exceto a de saúde.");
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Wallet API")
                                .version("0.0.1")
                                .description(
                                        "Serviço de carteira digital: saldo em BRL, crédito e débito idempotentes e"
                                                + " extrato imutável. As regras de consistência (saldo nunca"
                                                + " negativo, nenhuma operação aplicada em dobro) são garantidas"
                                                + " pelo banco (Oracle, PL/SQL)."))
                .components(new Components().addSecuritySchemes(ESQUEMA_API_KEY, apiKey))
                // Exigência global: o botão "Authorize" do Swagger UI passa a mandar o header em
                // todas as chamadas.
                .addSecurityItem(new SecurityRequirement().addList(ESQUEMA_API_KEY));
    }

    @Bean
    public OpenApiCustomizer respostasDeErro() {
        return new RespostasDeErroCustomizer();
    }
}
