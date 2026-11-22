package br.com.renanfranca.walletapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Exige o header {@code X-API-Key} em todas as rotas, exceto a de saúde ({@code /actuator/health}),
 * que o orquestrador e o monitoramento precisam chamar sem credencial.
 *
 * <p>Analogia Node: é um middleware do Express ({@code app.use((req, res, next) => ...)}) que roda
 * ANTES dos controllers.
 *
 * <p>Detalhes que importam:
 *
 * <ul>
 *   <li>A comparação é em tempo constante (compara os hashes SHA-256, de tamanho fixo): comparar
 *       texto com {@code equals} termina no primeiro caractere diferente, e o tempo de resposta
 *       poderia revelar a chave aos poucos.
 *   <li>O erro é entregue ao {@link HandlerExceptionResolver}: assim o {@code
 *       GlobalExceptionHandler} monta o {@code 401} no mesmo formato de todos os outros erros. Um
 *       filtro roda antes do Spring MVC e não passa pelo {@code @RestControllerAdvice} sozinho.
 *   <li>A chave nunca é escrita em log.
 * </ul>
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-API-Key";
    private static final String CAMINHO_PUBLICO = "/actuator/health";

    private final byte[] hashDaChaveEsperada;
    private final HandlerExceptionResolver resolvedorDeErros;

    public ApiKeyFilter(String chaveEsperada, HandlerExceptionResolver resolvedorDeErros) {
        this.hashDaChaveEsperada = sha256(chaveEsperada);
        this.resolvedorDeErros = resolvedorDeErros;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String caminho = request.getRequestURI();
        return caminho.equals(CAMINHO_PUBLICO) || caminho.startsWith(CAMINHO_PUBLICO + "/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String recebida = request.getHeader(HEADER);
        if (recebida == null || !MessageDigest.isEqual(hashDaChaveEsperada, sha256(recebida))) {
            resolvedorDeErros.resolveException(
                    request, response, null, new ChaveApiInvalidaException());
            return;
        }
        chain.doFilter(request, response);
    }

    private static byte[] sha256(String texto) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(texto.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 é obrigatório em toda JVM; se faltar, o ambiente está quebrado.
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
