package br.com.renanfranca.walletapi.dto;

import br.com.renanfranca.walletapi.model.Pagina;
import java.util.List;
import java.util.function.Function;

/** Página devolvida pela API: os itens mais os números de que o cliente precisa para paginar. */
public record PaginaResponse<T>(
        List<T> itens, int pagina, int tamanho, long totalItens, int totalPaginas) {

    public static <E, R> PaginaResponse<R> de(Pagina<E> pagina, Function<E, R> conversor) {
        return new PaginaResponse<>(
                pagina.itens().stream().map(conversor).toList(),
                pagina.pagina(),
                pagina.tamanho(),
                pagina.totalItens(),
                pagina.totalPaginas());
    }
}
