package br.com.renanfranca.walletapi.exception;

/**
 * Erro de negocio detectado pela propria aplicacao (por exemplo, GET de uma carteira que nao existe).
 * Os erros que nascem no banco chegam como DataAccessException e sao traduzidos no mesmo lugar
 * (GlobalExceptionHandler), para a resposta ser identica nos dois casos.
 */
public class ErroNegocioException extends RuntimeException {

    private final ErroNegocio erro;

    public ErroNegocioException(ErroNegocio erro) {
        super(erro.mensagem());
        this.erro = erro;
    }

    public ErroNegocio getErro() {
        return erro;
    }
}
