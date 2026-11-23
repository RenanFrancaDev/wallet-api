package br.com.renanfranca.walletapi.exception;

import java.util.Optional;
import org.springframework.http.HttpStatus;

/**
 * Catalogo dos erros de negocio do servico: cada um liga o codigo que sai do banco
 * (RAISE_APPLICATION_ERROR, faixa -20000 a -20999) ao status HTTP e a mensagem devolvida ao
 * cliente.
 *
 * <p>E a UNICA fonte da verdade dessa traducao. Os codigos aqui espelham o PKG_WALLET e o README.
 *
 * <p>Por que cada status:
 *
 * <ul>
 *   <li>400: a requisicao e invalida em si (tipo, valor, chave, formato).
 *   <li>404: o recurso pedido nao existe.
 *   <li>409: conflito com o estado atual (chave de idempotencia ja usada por outra operacao;
 *       extrato imutavel).
 *   <li>422: a requisicao esta bem formada, mas viola uma regra de negocio (saldo insuficiente).
 *   <li>503: falha transitoria (carteira ocupada); o cliente pode tentar de novo.
 * </ul>
 */
public enum ErroNegocio {
    CARTEIRA_NAO_ENCONTRADA(20001, HttpStatus.NOT_FOUND, "Carteira não encontrada."),
    DADOS_INVALIDOS(20002, HttpStatus.BAD_REQUEST, "Dados inválidos."),
    SALDO_INSUFICIENTE(
            20003, HttpStatus.UNPROCESSABLE_CONTENT, "Saldo insuficiente para a operação."),
    CHAVE_IDEMPOTENCIA_CONFLITO(
            20004, HttpStatus.CONFLICT, "A chave de idempotência já foi usada em outra operação."),
    EXTRATO_IMUTAVEL(
            20005,
            HttpStatus.CONFLICT,
            "O extrato é imutável: lançamentos não podem ser alterados nem apagados."),
    CARTEIRA_OCUPADA(
            20006,
            HttpStatus.SERVICE_UNAVAILABLE,
            "Carteira ocupada. Tente novamente em instantes.");

    private final int codigoOracle;
    private final HttpStatus status;
    private final String mensagem;

    ErroNegocio(int codigoOracle, HttpStatus status, String mensagem) {
        this.codigoOracle = codigoOracle;
        this.status = status;
        this.mensagem = mensagem;
    }

    public int codigoOracle() {
        return codigoOracle;
    }

    public HttpStatus status() {
        return status;
    }

    public String mensagem() {
        return mensagem;
    }

    /**
     * Encontra o erro a partir do codigo do Oracle. Aceita 20003 (o que o JDBC devolve em
     * SQLException.getErrorCode()) e -20003 (o SQLCODE do PL/SQL).
     */
    public static Optional<ErroNegocio> deCodigoOracle(int codigo) {
        int positivo = Math.abs(codigo);
        for (ErroNegocio erro : values()) {
            if (erro.codigoOracle == positivo) {
                return Optional.of(erro);
            }
        }
        return Optional.empty();
    }
}
