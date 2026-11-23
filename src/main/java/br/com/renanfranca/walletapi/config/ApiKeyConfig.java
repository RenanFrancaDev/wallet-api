package br.com.renanfranca.walletapi.config;

import br.com.renanfranca.walletapi.security.ApiKeyFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Liga o filtro de API key em todas as rotas.
 *
 * <p>FALHA FECHADA: a chave vem de {@code wallet.api-key} (a variável de ambiente {@code
 * WALLET_API_KEY}, do mesmo {@code .env} das senhas). Se faltar, o Spring nem consegue resolver o
 * placeholder e a aplicação não sobe; se for curta demais, este construtor recusa. Nunca existe o
 * caso "sem chave, API aberta".
 */
@Configuration
public class ApiKeyConfig {

    static final int TAMANHO_MINIMO = 16;

    private final String chave;

    public ApiKeyConfig(@Value("${wallet.api-key}") String chave) {
        if (chave == null || chave.length() < TAMANHO_MINIMO) {
            throw new IllegalStateException(
                    "wallet.api-key (variável WALLET_API_KEY) deve ter pelo menos "
                            + TAMANHO_MINIMO
                            + " caracteres");
        }
        this.chave = chave;
    }

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolvedorDeErros) {
        FilterRegistrationBean<ApiKeyFilter> registro =
                new FilterRegistrationBean<>(new ApiKeyFilter(chave, resolvedorDeErros));
        registro.addUrlPatterns("/*");
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registro;
    }
}
