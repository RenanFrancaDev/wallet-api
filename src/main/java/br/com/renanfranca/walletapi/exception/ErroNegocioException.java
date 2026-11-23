package br.com.renanfranca.walletapi.exception;

import java.util.List;

/**
 * Erro de negocio detectado pela propria aplicacao (por exemplo, GET de uma carteira que nao
 * existe). Os erros que nascem no banco chegam como DataAccessException e sao traduzidos no mesmo
 * lugar (GlobalExceptionHandler), para a resposta ser identica nos dois casos.
 */
public class ErroNegocioException extends RuntimeException {

    /**
     * Toda excecao Java e serializavel; declarar a versao evita o aviso do compilador
     * (-Xlint:serial).
     */
    private static final long serialVersionUID = 1L;

    private final ErroNegocio erro;
    private final transient List<String> detalhes;

    public ErroNegocioException(ErroNegocio erro) {
        this(erro, List.of());
    }

    /** Com detalhes: o motivo exato do erro (ex.: "size: deve estar entre 1 e 100"). */
    public ErroNegocioException(ErroNegocio erro, List<String> detalhes) {
        super(erro.mensagem());
        this.erro = erro;
        this.detalhes = List.copyOf(detalhes);
    }

    public ErroNegocio getErro() {
        return erro;
    }

    public List<String> getDetalhes() {
        return detalhes;
    }
}
