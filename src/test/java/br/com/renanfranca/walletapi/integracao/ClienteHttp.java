package br.com.renanfranca.walletapi.integracao;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Cliente HTTP dos testes de integração: chama a aplicação de verdade (Tomcat real, na porta
 * sorteada), sem MockMvc.
 *
 * <p>Usa só o {@code HttpClient} do JDK, que é seguro entre threads: o teste concorrente dispara
 * dezenas de requisições ao mesmo tempo com a mesma instância. Analogia Node: um {@code fetch} com
 * os headers já preenchidos.
 */
final class ClienteHttp {

    /** O que o teste precisa saber de uma resposta. */
    record Resposta(int status, String corpo, HttpHeaders cabecalhos) {

        String cabecalho(String nome) {
            return cabecalhos.firstValue(nome).orElse(null);
        }

        /** Lê um valor do corpo JSON por expressão JsonPath, ex.: {@code "$.saldoApos"}. */
        <T> T json(String expressao) {
            return JsonPath.read(corpo, expressao);
        }
    }

    private final HttpClient http =
            HttpClient.newBuilder()
                    // HTTP/1.1 explícito: o padrão tenta subir para HTTP/2 em texto puro, o que não
                    // interessa aqui.
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

    private final String base;
    private final String chaveApi;

    /**
     * @param chaveApi valor do header X-API-Key, ou {@code null} para NÃO enviar o header
     */
    ClienteHttp(int porta, String chaveApi) {
        this.base = "http://localhost:" + porta;
        this.chaveApi = chaveApi;
    }

    Resposta get(String caminho) {
        return enviar(construir(caminho).GET().build());
    }

    /** POST sem corpo e sem chave de idempotência (ex.: criar carteira). */
    Resposta post(String caminho) {
        return enviar(construir(caminho).POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    /** POST de operação: com {@code Idempotency-Key} (se não for nula) e corpo JSON. */
    Resposta post(String caminho, String chaveIdempotencia, String corpoJson) {
        HttpRequest.Builder requisicao =
                construir(caminho)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(corpoJson));
        if (chaveIdempotencia != null) {
            requisicao.header("Idempotency-Key", chaveIdempotencia);
        }
        return enviar(requisicao.build());
    }

    private HttpRequest.Builder construir(String caminho) {
        HttpRequest.Builder requisicao =
                HttpRequest.newBuilder(URI.create(base + caminho)).timeout(Duration.ofSeconds(60));
        if (chaveApi != null) {
            requisicao.header("X-API-Key", chaveApi);
        }
        return requisicao;
    }

    private Resposta enviar(HttpRequest requisicao) {
        try {
            HttpResponse<String> resposta =
                    http.send(requisicao, HttpResponse.BodyHandlers.ofString());
            return new Resposta(resposta.statusCode(), resposta.body(), resposta.headers());
        } catch (IOException e) {
            throw new IllegalStateException("Falha de rede em " + requisicao.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Requisição interrompida: " + requisicao.uri(), e);
        }
    }
}
