package br.com.renanfranca.walletapi.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.renanfranca.walletapi.config.ApiKeyConfig;
import br.com.renanfranca.walletapi.controller.CarteiraController;
import br.com.renanfranca.walletapi.model.Carteira;
import br.com.renanfranca.walletapi.service.CarteiraService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Filtro de API key, testado com o Spring MVC real e o serviço simulado. O {@code ApiKeyConfig} é
 * importado de propósito: as outras fatias web não o carregam, então os testes dos controllers
 * seguem sem precisar de chave.
 */
@WebMvcTest(CarteiraController.class)
@Import(ApiKeyConfig.class)
@TestPropertySource(properties = "wallet.api-key=chave-de-teste-123456")
class ApiKeyFilterTest {

    private static final String CHAVE = "chave-de-teste-123456";

    @Autowired private MockMvc mvc;

    @MockitoBean private CarteiraService service;

    private void carteiraExiste() {
        when(service.buscar(1L))
                .thenReturn(new Carteira(1, "Maria", BigDecimal.ZERO, OffsetDateTime.now()));
    }

    @Test
    void comAChaveCorretaOPedidoPassa() throws Exception {
        carteiraExiste();

        mvc.perform(get("/wallets/1").header("X-API-Key", CHAVE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void semOHeaderDevolve401NoFormatoPadraoESemChamarOServico() throws Exception {
        mvc.perform(get("/wallets/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NAO_AUTORIZADO"))
                .andExpect(jsonPath("$.mensagem").value("API key ausente ou inválida."))
                .andExpect(jsonPath("$.path").value("/wallets/1"))
                .andExpect(jsonPath("$.timestamp").exists());

        verifyNoInteractions(service);
    }

    @Test
    void chaveErradaDevolve401() throws Exception {
        mvc.perform(get("/wallets/1").header("X-API-Key", "chave-errada-qualquer"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NAO_AUTORIZADO"));

        verifyNoInteractions(service);
    }

    @Test
    void chaveVaziaDevolve401() throws Exception {
        mvc.perform(get("/wallets/1").header("X-API-Key", "")).andExpect(status().isUnauthorized());
    }

    @Test
    void chaveQuaseCertaDevolve401() throws Exception {
        // Mesmo tamanho, um caractere diferente: a comparação precisa ser exata.
        mvc.perform(get("/wallets/1").header("X-API-Key", "chave-de-teste-123457"))
                .andExpect(status().isUnauthorized());
        // Prefixo da chave verdadeira.
        mvc.perform(get("/wallets/1").header("X-API-Key", "chave-de-teste"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aMensagemDeErroNaoRevelaSeFaltouOuSeEstavaErrada() throws Exception {
        String semHeader =
                mvc.perform(get("/wallets/1")).andReturn().getResponse().getContentAsString();
        String chaveErrada =
                mvc.perform(get("/wallets/1").header("X-API-Key", "x"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // Só o timestamp muda entre as duas respostas; a mensagem é a mesma.
        org.assertj.core.api.Assertions.assertThat(semHeader)
                .contains("API key ausente ou inválida.");
        org.assertj.core.api.Assertions.assertThat(chaveErrada)
                .contains("API key ausente ou inválida.");
    }

    @Test
    void escritaSemChaveTambemEBarradaAntesDoControllerERejeitaOCorpo() throws Exception {
        mvc.perform(
                        post("/wallets")
                                .contentType("application/json")
                                .content("{\"titular\":\"Maria\"}"))
                .andExpect(status().isUnauthorized());

        verify(service, never()).criar(any());
    }

    @Test
    void rotaInexistenteSemChaveTambemDevolve401EscondendoQuaisRotasExistem() throws Exception {
        mvc.perform(get("/nao-existe")).andExpect(status().isUnauthorized());
    }

    @Test
    void rotaDeSaudeNaoExigeChave() throws Exception {
        // A fatia web não tem o Actuator (por isso 404), mas o que importa é NÃO ser 401: o filtro
        // deixou passar.
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(not(containsString("NAO_AUTORIZADO"))));
    }

    @Test
    void filtroNaoTrataCaminhoParecidoComSaudeComoPublico() throws Exception {
        mvc.perform(get("/actuator/healthz")).andExpect(status().isUnauthorized());
        mvc.perform(get("/wallets/actuator/health")).andExpect(status().isUnauthorized());
        verify(service, never()).buscar(anyLong());
    }

    @Test
    void documentacaoDaApiNaoExigeChave() throws Exception {
        // A fatia web não tem o springdoc (por isso 404), mas o que importa é NÃO ser 401: o filtro
        // deixou passar.
        for (String caminho :
                new String[] {
                    "/v3/api-docs",
                    "/v3/api-docs/swagger-config",
                    "/swagger-ui.html",
                    "/swagger-ui/index.html"
                }) {
            mvc.perform(get(caminho))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.codigo").value(not(containsString("NAO_AUTORIZADO"))));
        }
    }

    @Test
    void caminhoParecidoComADocumentacaoNaoEPublico() throws Exception {
        mvc.perform(get("/swagger-uix")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docsx")).andExpect(status().isUnauthorized());
        mvc.perform(get("/wallets/v3/api-docs")).andExpect(status().isUnauthorized());
    }
}
