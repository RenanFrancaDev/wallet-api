package br.com.renanfranca.walletapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.renanfranca.walletapi.exception.ErroNegocio;
import br.com.renanfranca.walletapi.exception.ErroNegocioException;
import br.com.renanfranca.walletapi.model.Carteira;
import br.com.renanfranca.walletapi.model.Lancamento;
import br.com.renanfranca.walletapi.model.Pagina;
import br.com.renanfranca.walletapi.model.TipoOperacao;
import br.com.renanfranca.walletapi.repository.CarteiraRepository;
import br.com.renanfranca.walletapi.repository.LancamentoRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Regras do extrato: validação dos parâmetros, carteira inexistente e o cálculo do deslocamento.
 */
class ExtratoServiceTest {

    private static final OffsetDateTime INICIO = OffsetDateTime.parse("2026-11-01T00:00:00Z");
    private static final OffsetDateTime FIM = OffsetDateTime.parse("2026-12-01T00:00:00Z");

    private CarteiraRepository carteiraRepository;
    private LancamentoRepository lancamentoRepository;
    private ExtratoService service;

    @BeforeEach
    void preparar() {
        carteiraRepository = mock(CarteiraRepository.class);
        lancamentoRepository = mock(LancamentoRepository.class);
        service = new ExtratoService(carteiraRepository, lancamentoRepository);
    }

    private void carteiraExiste() {
        when(carteiraRepository.buscarPorId(1L))
                .thenReturn(
                        Optional.of(
                                new Carteira(1, "Maria", BigDecimal.ZERO, OffsetDateTime.now())));
    }

    private static Lancamento lancamento(long id) {
        return new Lancamento(
                id,
                1,
                TipoOperacao.CREDITO,
                BigDecimal.TEN,
                BigDecimal.TEN,
                "k" + id,
                OffsetDateTime.now());
    }

    @Test
    void devolveAPaginaComOTotalEOsItens() {
        carteiraExiste();
        when(lancamentoRepository.contar(1L, INICIO, FIM)).thenReturn(45L);
        when(lancamentoRepository.listar(1L, INICIO, FIM, 20L, 20))
                .thenReturn(List.of(lancamento(2), lancamento(1)));

        Pagina<Lancamento> pagina = service.consultar(1L, INICIO, FIM, 1, 20);

        assertThat(pagina.itens()).hasSize(2);
        assertThat(pagina.pagina()).isEqualTo(1);
        assertThat(pagina.totalItens()).isEqualTo(45);
        assertThat(pagina.totalPaginas()).isEqualTo(3);
    }

    @ParameterizedTest(name = "pagina {0}, tamanho {1} -> deslocamento {2}")
    @CsvSource({"0, 20, 0", "1, 20, 20", "3, 10, 30", "2, 100, 200"})
    void calculaODeslocamentoComoPaginaVezesTamanho(int pagina, int tamanho, long deslocamento) {
        carteiraExiste();
        when(lancamentoRepository.contar(1L, null, null)).thenReturn(1000L);

        service.consultar(1L, null, null, pagina, tamanho);

        verify(lancamentoRepository).listar(1L, null, null, deslocamento, tamanho);
    }

    @Test
    void extratoVazioNaoConsultaAListagem() {
        carteiraExiste();
        when(lancamentoRepository.contar(1L, null, null)).thenReturn(0L);

        Pagina<Lancamento> pagina = service.consultar(1L, null, null, 0, 20);

        assertThat(pagina.itens()).isEmpty();
        assertThat(pagina.totalPaginas()).isZero();
        verify(lancamentoRepository, never()).listar(anyLong(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void carteiraInexistenteLancaErro404SemConsultarOExtrato() {
        when(carteiraRepository.buscarPorId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consultar(99L, null, null, 0, 20))
                .isInstanceOfSatisfying(
                        ErroNegocioException.class,
                        e ->
                                assertThat(e.getErro())
                                        .isEqualTo(ErroNegocio.CARTEIRA_NAO_ENCONTRADA));
        verifyNoInteractions(lancamentoRepository);
    }

    @ParameterizedTest(name = "pagina {0}, tamanho {1} -> inválido ({2})")
    @CsvSource({
        "-1, 20, page",
        "0, 0, size",
        "0, 101, size",
        "0, -5, size",
    })
    void parametrosDePaginacaoInvalidosViramDadosInvalidos(int pagina, int tamanho, String campo) {
        assertThatThrownBy(() -> service.consultar(1L, null, null, pagina, tamanho))
                .isInstanceOfSatisfying(
                        ErroNegocioException.class,
                        e -> {
                            assertThat(e.getErro()).isEqualTo(ErroNegocio.DADOS_INVALIDOS);
                            assertThat(e.getDetalhes())
                                    .singleElement()
                                    .asString()
                                    .startsWith(campo);
                        });
        verifyNoInteractions(carteiraRepository, lancamentoRepository);
    }

    @Test
    void periodoComInicioNaoAnteriorAoFimEInvalido() {
        assertThatThrownBy(() -> service.consultar(1L, FIM, INICIO, 0, 20))
                .isInstanceOfSatisfying(
                        ErroNegocioException.class,
                        e ->
                                assertThat(e.getDetalhes())
                                        .containsExactly("from: deve ser anterior a to"));
        assertThatThrownBy(() -> service.consultar(1L, INICIO, INICIO, 0, 20))
                .isInstanceOf(ErroNegocioException.class);
    }

    @Test
    void variosParametrosInvalidosSaemTodosNosDetalhes() {
        assertThatThrownBy(() -> service.consultar(1L, FIM, INICIO, -1, 0))
                .isInstanceOfSatisfying(
                        ErroNegocioException.class, e -> assertThat(e.getDetalhes()).hasSize(3));
    }
}
