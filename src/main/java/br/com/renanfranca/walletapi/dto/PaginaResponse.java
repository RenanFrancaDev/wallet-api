package br.com.renanfranca.walletapi.dto;

import br.com.renanfranca.walletapi.model.Pagina;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;

/** Página devolvida pela API: os itens mais os números de que o cliente precisa para paginar. */
public record PaginaResponse<T>(
        @Schema(description = "Itens desta página") List<T> itens,
        @Schema(description = "Número desta página, a partir de 0", example = "0") int pagina,
        @Schema(description = "Itens por página pedidos", example = "20") int tamanho,
        @Schema(
                        description = "Quantos itens existem no total (no período filtrado)",
                        example = "45")
                long totalItens,
        @Schema(description = "Quantas páginas existem no total", example = "3") int totalPaginas) {

    public static <E, R> PaginaResponse<R> de(Pagina<E> pagina, Function<E, R> conversor) {
        return new PaginaResponse<>(
                pagina.itens().stream().map(conversor).toList(),
                pagina.pagina(),
                pagina.tamanho(),
                pagina.totalItens(),
                pagina.totalPaginas());
    }
}
