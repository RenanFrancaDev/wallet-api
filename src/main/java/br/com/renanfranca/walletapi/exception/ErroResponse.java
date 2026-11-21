package br.com.renanfranca.walletapi.exception;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Payload padrao de erro de toda a API.
 *
 * @param codigo   identificador estavel do erro, para o cliente tratar por codigo (ex.: SALDO_INSUFICIENTE)
 * @param mensagem texto legivel para humanos
 * @param timestamp momento em que o erro foi gerado
 * @param path     caminho da requisicao que falhou
 * @param detalhes informacoes adicionais (ex.: campos invalidos); lista vazia quando nao ha
 */
public record ErroResponse(String codigo, String mensagem, OffsetDateTime timestamp, String path,
        List<String> detalhes) {
}
