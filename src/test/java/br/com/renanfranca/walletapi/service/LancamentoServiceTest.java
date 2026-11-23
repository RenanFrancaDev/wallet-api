package br.com.renanfranca.walletapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.renanfranca.walletapi.model.Lancamento;
import br.com.renanfranca.walletapi.model.OperacaoRegistrada;
import br.com.renanfranca.walletapi.model.TipoOperacao;
import br.com.renanfranca.walletapi.repository.LancamentoRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Serviço com o repositório simulado (o @Transactional não age aqui, só a lógica do serviço). */
class LancamentoServiceTest {

    private LancamentoRepository repository;
    private LancamentoService service;

    @BeforeEach
    void preparar() {
        repository = mock(LancamentoRepository.class);
        service = new LancamentoService(repository);
    }

    private static OperacaoRegistrada resultado(boolean repetida) {
        Lancamento lancamento =
                new Lancamento(
                        7,
                        1,
                        TipoOperacao.DEBITO,
                        new BigDecimal("30"),
                        new BigDecimal("70"),
                        "k1",
                        OffsetDateTime.now());
        return new OperacaoRegistrada(lancamento, repetida);
    }

    @Test
    void repassaAOperacaoAoRepositorioEDevolveOResultado() {
        BigDecimal valor = new BigDecimal("30");
        when(repository.registrar(1L, TipoOperacao.DEBITO, valor, "k1"))
                .thenReturn(resultado(false));

        OperacaoRegistrada devolvido = service.registrar(1L, TipoOperacao.DEBITO, valor, "k1");

        assertThat(devolvido.repetida()).isFalse();
        assertThat(devolvido.lancamento().saldoApos()).isEqualByComparingTo("70");
        verify(repository).registrar(1L, TipoOperacao.DEBITO, valor, "k1");
    }

    @Test
    void repeticaoVemMarcadaComoRepetida() {
        BigDecimal valor = new BigDecimal("30");
        when(repository.registrar(1L, TipoOperacao.DEBITO, valor, "k1"))
                .thenReturn(resultado(true));

        assertThat(service.registrar(1L, TipoOperacao.DEBITO, valor, "k1").repetida()).isTrue();
    }

    @Test
    void errosDoRepositorioSobemSemSerEngolidos() {
        BigDecimal valor = BigDecimal.TEN;
        when(repository.registrar(1L, TipoOperacao.CREDITO, valor, "k1"))
                .thenThrow(new DataIntegrityViolationException("falha"));

        assertThatThrownBy(() -> service.registrar(1L, TipoOperacao.CREDITO, valor, "k1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
