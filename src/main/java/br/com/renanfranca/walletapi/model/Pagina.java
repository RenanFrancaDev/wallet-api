package br.com.renanfranca.walletapi.model;

import java.util.List;

/**
 * Uma página de resultados. {@code pagina} começa em 0.
 *
 * @param totalItens quantos itens existem no total (para o cliente saber quantas páginas há)
 */
public record Pagina<T>(List<T> itens, int pagina, int tamanho, long totalItens) {

    public int totalPaginas() {
        return (int) ((totalItens + tamanho - 1) / tamanho);
    }
}
