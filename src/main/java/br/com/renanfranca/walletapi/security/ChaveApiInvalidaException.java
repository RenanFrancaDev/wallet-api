package br.com.renanfranca.walletapi.security;

/**
 * API key ausente ou inválida. Lançada pelo {@link ApiKeyFilter} e traduzida para {@code 401} no
 * formato padrão de erro pelo GlobalExceptionHandler. A mensagem não diz se faltou ou se estava
 * errada, para não ajudar quem tenta adivinhar.
 */
public class ChaveApiInvalidaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ChaveApiInvalidaException() {
        super("API key ausente ou inválida.");
    }
}
