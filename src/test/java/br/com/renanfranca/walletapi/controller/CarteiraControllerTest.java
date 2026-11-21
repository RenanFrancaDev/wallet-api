package br.com.renanfranca.walletapi.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import br.com.renanfranca.walletapi.exception.ErroNegocio;
import br.com.renanfranca.walletapi.exception.ErroNegocioException;
import br.com.renanfranca.walletapi.model.Carteira;
import br.com.renanfranca.walletapi.service.CarteiraService;

/**
 * Camada web: sobe so o Spring MVC (sem banco), com o servico simulado, e dispara requisicoes HTTP de mentira.
 * Analogia Node: supertest em cima do app Express, com o servico mockado.
 */
@WebMvcTest(CarteiraController.class)
class CarteiraControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CarteiraService service;

    private static Carteira carteira(long id, String titular) {
        return new Carteira(id, titular, new BigDecimal("0.00"), OffsetDateTime.parse("2026-11-21T10:00:00-03:00"));
    }

    @Test
    void postSemCorpoCriaCarteiraDevolve201ELocation() throws Exception {
        when(service.criar(null)).thenReturn(carteira(1, null));

        mvc.perform(post("/wallets"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/wallets/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.saldo").value(0.0));
    }

    @Test
    void postComTitularRepassaOTitularAoServico() throws Exception {
        when(service.criar("Maria")).thenReturn(carteira(2, "Maria"));

        mvc.perform(post("/wallets").contentType(MediaType.APPLICATION_JSON).content("{\"titular\":\"Maria\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.titular").value("Maria"));

        verify(service).criar("Maria");
    }

    @Test
    void postComTitularLongoDemaisDevolve400ComOCampoNosDetalhes() throws Exception {
        String longo = "x".repeat(121);

        mvc.perform(post("/wallets").contentType(MediaType.APPLICATION_JSON)
                .content("{\"titular\":\"" + longo + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.path").value("/wallets"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.detalhes[0]").value(org.hamcrest.Matchers.startsWith("titular:")));
    }

    @Test
    void postComJsonMalformadoDevolve400NoFormatoPadrao() throws Exception {
        mvc.perform(post("/wallets").contentType(MediaType.APPLICATION_JSON).content("{titular:"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"));
    }

    @Test
    void getDevolveACarteira() throws Exception {
        when(service.buscar(5L)).thenReturn(carteira(5, "Joao"));

        mvc.perform(get("/wallets/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.titular").value("Joao"));
    }

    @Test
    void getDeCarteiraInexistenteDevolve404() throws Exception {
        when(service.buscar(99L)).thenThrow(new ErroNegocioException(ErroNegocio.CARTEIRA_NAO_ENCONTRADA));

        mvc.perform(get("/wallets/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("CARTEIRA_NAO_ENCONTRADA"))
                .andExpect(jsonPath("$.mensagem").value("Carteira não encontrada."))
                .andExpect(jsonPath("$.path").value("/wallets/99"));
    }

    @Test
    void getComIdNaoNumericoDevolve400() throws Exception {
        mvc.perform(get("/wallets/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"));
    }

    @Test
    void erroDoBancoLancadoPeloServicoETraduzidoNaCamadaWeb() throws Exception {
        // Prova a ligacao completa: o servico lanca o que o driver lanca, a API responde 422 padronizado.
        SQLException sql = new SQLException("ORA-20003: Saldo insuficiente.", "72000", 20003);
        when(service.buscar(any(Long.class))).thenThrow(new UncategorizedSQLException("task", "sql", sql));

        mvc.perform(get("/wallets/1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("SALDO_INSUFICIENTE"));
    }

    @Test
    void rotaInexistenteDevolve404NoFormatoPadrao() throws Exception {
        mvc.perform(get("/nao-existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NOT_FOUND"));
    }

    @Test
    void excecaoInesperadaDevolve500SemDetalhes() throws Exception {
        when(service.buscar(1L)).thenThrow(new RuntimeException("segredo interno"));

        mvc.perform(get("/wallets/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.codigo").value("ERRO_INTERNO"))
                .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("segredo"))));
    }
}
