package br.com.renanfranca.walletapi.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Cálculo do total de páginas a partir do total de itens e do tamanho da página. */
class PaginaTest {

    @ParameterizedTest(name = "{0} itens, tamanho {1} -> {2} páginas")
    @CsvSource({"0, 20, 0", "1, 20, 1", "20, 20, 1", "21, 20, 2", "41, 20, 3", "5, 2, 3"})
    void calculaOTotalDePaginas(long totalItens, int tamanho, int paginasEsperadas) {
        Pagina<String> pagina = new Pagina<>(List.of(), 0, tamanho, totalItens);

        assertThat(pagina.totalPaginas()).isEqualTo(paginasEsperadas);
    }
}
