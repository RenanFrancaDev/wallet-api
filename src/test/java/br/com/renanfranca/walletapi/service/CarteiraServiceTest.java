package br.com.renanfranca.walletapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.renanfranca.walletapi.exception.ErroNegocio;
import br.com.renanfranca.walletapi.exception.ErroNegocioException;
import br.com.renanfranca.walletapi.model.Carteira;
import br.com.renanfranca.walletapi.repository.CarteiraRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Servico com o repositorio simulado (Mockito, o jest.fn() do Java). Sem Spring: o @Transactional
 * nao age aqui, so a logica do servico e testada.
 */
class CarteiraServiceTest {

    private CarteiraRepository repository;
    private CarteiraService service;

    @BeforeEach
    void preparar() {
        repository = mock(CarteiraRepository.class);
        service = new CarteiraService(repository);
    }

    private static Carteira carteira(long id) {
        return new Carteira(id, "Maria", new BigDecimal("0.00"), OffsetDateTime.now());
    }

    @Test
    void criarChamaOPackageELeACarteiraCriada() {
        when(repository.criar("Maria")).thenReturn(7L);
        when(repository.buscarPorId(7L)).thenReturn(Optional.of(carteira(7)));

        Carteira criada = service.criar("Maria");

        assertThat(criada.id()).isEqualTo(7L);
        assertThat(criada.saldo()).isEqualByComparingTo("0");
        verify(repository).criar("Maria");
    }

    @Test
    void criarAceitaTitularNulo() {
        when(repository.criar(null)).thenReturn(8L);
        when(repository.buscarPorId(8L)).thenReturn(Optional.of(carteira(8)));

        assertThat(service.criar(null).id()).isEqualTo(8L);
    }

    @Test
    void criarFalhaSeACarteiraNaoForEncontradaDepoisDeCriada() {
        when(repository.criar("x")).thenReturn(9L);
        when(repository.buscarPorId(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.criar("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void buscarDevolveACarteiraExistente() {
        when(repository.buscarPorId(1L)).thenReturn(Optional.of(carteira(1)));

        assertThat(service.buscar(1L).id()).isEqualTo(1L);
    }

    @Test
    void buscarCarteiraInexistenteLancaErroDeNegocio404() {
        when(repository.buscarPorId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.buscar(99L))
                .isInstanceOf(ErroNegocioException.class)
                .extracting(e -> ((ErroNegocioException) e).getErro())
                .isEqualTo(ErroNegocio.CARTEIRA_NAO_ENCONTRADA);
        verify(repository, never()).criar("qualquer");
    }
}
