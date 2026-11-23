package br.com.renanfranca.walletapi.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.renanfranca.walletapi.exception.ErroNegocio;
import br.com.renanfranca.walletapi.exception.ErroNegocioException;
import br.com.renanfranca.walletapi.model.Lancamento;
import br.com.renanfranca.walletapi.model.Pagina;
import br.com.renanfranca.walletapi.model.TipoOperacao;
import br.com.renanfranca.walletapi.service.ExtratoService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Camada web de GET /wallets/{id}/transactions: serviço simulado, sem banco. */
@WebMvcTest(ExtratoController.class)
class ExtratoControllerTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private ExtratoService service;

    private static Lancamento lancamento(long id, String saldoApos) {
        return new Lancamento(
                id,
                1,
                TipoOperacao.CREDITO,
                new BigDecimal("10"),
                new BigDecimal(saldoApos),
                "k" + id,
                OffsetDateTime.parse("2026-11-22T10:00:00Z"));
    }

    @Test
    void semParametrosUsaPagina0Tamanho20ESemPeriodo() throws Exception {
        when(service.consultar(1L, null, null, 0, 20))
                .thenReturn(
                        new Pagina<>(List.of(lancamento(2, "20"), lancamento(1, "10")), 0, 20, 2));

        mvc.perform(get("/wallets/1/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(2))
                .andExpect(jsonPath("$.itens[0].id").value(2))
                .andExpect(jsonPath("$.itens[0].saldoApos").value(20.0))
                .andExpect(jsonPath("$.pagina").value(0))
                .andExpect(jsonPath("$.tamanho").value(20))
                .andExpect(jsonPath("$.totalItens").value(2))
                .andExpect(jsonPath("$.totalPaginas").value(1));

        verify(service).consultar(eq(1L), isNull(), isNull(), eq(0), eq(20));
    }

    @Test
    void repassaPaginaTamanhoEPeriodoAoServico() throws Exception {
        OffsetDateTime inicio = OffsetDateTime.parse("2026-11-01T00:00:00Z");
        OffsetDateTime fim = OffsetDateTime.parse("2026-12-01T00:00:00-03:00");
        when(service.consultar(1L, inicio, fim, 2, 5))
                .thenReturn(new Pagina<>(List.of(), 2, 5, 12));

        mvc.perform(
                        get("/wallets/1/transactions")
                                .param("from", "2026-11-01T00:00:00Z")
                                .param("to", "2026-12-01T00:00:00-03:00")
                                .param("page", "2")
                                .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagina").value(2))
                .andExpect(jsonPath("$.totalPaginas").value(3));

        verify(service).consultar(1L, inicio, fim, 2, 5);
    }

    @Test
    void dataInvalidaDevolve400() throws Exception {
        mvc.perform(get("/wallets/1/transactions").param("from", "ontem"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.detalhes[0]").value(containsString("from")));
    }

    @Test
    void paginaOuTamanhoNaoNumericosDevolvem400() throws Exception {
        mvc.perform(get("/wallets/1/transactions").param("page", "abc"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/wallets/1/transactions").param("size", "muitos"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validacaoDoServicoDevolve400ComOMotivoNosDetalhes() throws Exception {
        when(service.consultar(1L, null, null, 0, 500))
                .thenThrow(
                        new ErroNegocioException(
                                ErroNegocio.DADOS_INVALIDOS,
                                List.of("size: deve estar entre 1 e 100")));

        mvc.perform(get("/wallets/1/transactions").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.detalhes[0]").value("size: deve estar entre 1 e 100"));
    }

    @Test
    void carteiraInexistenteDevolve404() throws Exception {
        when(service.consultar(99L, null, null, 0, 20))
                .thenThrow(new ErroNegocioException(ErroNegocio.CARTEIRA_NAO_ENCONTRADA));

        mvc.perform(get("/wallets/99/transactions"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("CARTEIRA_NAO_ENCONTRADA"));
    }

    @Test
    void idDaCarteiraNaoNumericoDevolve400() throws Exception {
        mvc.perform(get("/wallets/abc/transactions")).andExpect(status().isBadRequest());
    }
}
