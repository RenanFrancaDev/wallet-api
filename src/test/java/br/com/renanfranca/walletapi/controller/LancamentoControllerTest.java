package br.com.renanfranca.walletapi.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.renanfranca.walletapi.model.Lancamento;
import br.com.renanfranca.walletapi.model.OperacaoRegistrada;
import br.com.renanfranca.walletapi.model.TipoOperacao;
import br.com.renanfranca.walletapi.service.LancamentoService;
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
import org.springframework.test.web.servlet.ResultActions;

/** Camada web de POST /wallets/{id}/transactions: serviço simulado, sem banco. */
@WebMvcTest(LancamentoController.class)
class LancamentoControllerTest {

    private static final String URL = "/wallets/1/transactions";

    @Autowired private MockMvc mvc;

    @MockitoBean private LancamentoService service;

    private static OperacaoRegistrada resultado(boolean repetida) {
        Lancamento lancamento =
                new Lancamento(
                        10,
                        1,
                        TipoOperacao.CREDITO,
                        new BigDecimal("100"),
                        new BigDecimal("100"),
                        "k1",
                        OffsetDateTime.parse("2026-11-22T10:00:00-03:00"));
        return new OperacaoRegistrada(lancamento, repetida);
    }

    private ResultActions enviar(String chave, String corpo) throws Exception {
        var requisicao = post(URL).contentType(MediaType.APPLICATION_JSON).content(corpo);
        return mvc.perform(
                chave == null ? requisicao : requisicao.header("Idempotency-Key", chave));
    }

    private static UncategorizedSQLException erroDoBanco(int codigoOra) {
        return new UncategorizedSQLException(
                "task",
                "sql",
                new SQLException("ORA-" + codigoOra + ": regra", "72000", codigoOra));
    }

    @Test
    void operacaoNovaDevolve201ComOLancamentoESemHeaderDeRepeticao() throws Exception {
        when(service.registrar(eq(1L), eq(TipoOperacao.CREDITO), any(), eq("k1")))
                .thenReturn(resultado(false));

        enviar("k1", "{\"tipo\":\"CREDITO\",\"valor\":100.00}")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.tipo").value("CREDITO"))
                .andExpect(jsonPath("$.saldoApos").value(100.0))
                .andExpect(jsonPath("$.chaveIdempotencia").value("k1"));

        verify(service)
                .registrar(
                        eq(1L),
                        eq(TipoOperacao.CREDITO),
                        argThat(valor -> valor.compareTo(new BigDecimal("100")) == 0),
                        eq("k1"));
    }

    @Test
    void repeticaoDevolveOMesmoResultadoComOHeaderDeRepeticao() throws Exception {
        when(service.registrar(anyLong(), any(), any(), eq("k1"))).thenReturn(resultado(true));

        enviar("k1", "{\"tipo\":\"CREDITO\",\"valor\":100.00}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(10));
    }

    @Test
    void semOHeaderIdempotencyKeyDevolve400() throws Exception {
        enviar(null, "{\"tipo\":\"CREDITO\",\"valor\":10}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.detalhes[0]").value(containsString("Idempotency-Key")));

        verify(service, never()).registrar(anyLong(), any(), any(), any());
    }

    @Test
    void tipoInvalidoDevolve400() throws Exception {
        enviar("k1", "{\"tipo\":\"PIX\",\"valor\":10}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"));
    }

    @Test
    void tipoAusenteDevolve400ComOCampoNosDetalhes() throws Exception {
        enviar("k1", "{\"valor\":10}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalhes[0]").value(containsString("tipo")));
    }

    @Test
    void valorZeroOuNegativoDevolve400() throws Exception {
        enviar("k1", "{\"tipo\":\"DEBITO\",\"valor\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalhes[0]").value(containsString("maior que zero")));
        enviar("k1", "{\"tipo\":\"DEBITO\",\"valor\":-5}").andExpect(status().isBadRequest());
    }

    @Test
    void valorComMaisDeDuasCasasDecimaisDevolve400() throws Exception {
        enviar("k1", "{\"tipo\":\"CREDITO\",\"valor\":10.005}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalhes[0]").value(containsString("2 casas decimais")));
    }

    @Test
    void valorAusenteOuCorpoAusenteDevolve400() throws Exception {
        enviar("k1", "{\"tipo\":\"CREDITO\"}").andExpect(status().isBadRequest());
        mvc.perform(post(URL).header("Idempotency-Key", "k1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"));
    }

    @Test
    void idDaCarteiraNaoNumericoDevolve400() throws Exception {
        mvc.perform(
                        post("/wallets/abc/transactions")
                                .header("Idempotency-Key", "k1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"tipo\":\"CREDITO\",\"valor\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"));
    }

    @Test
    void saldoInsuficienteVindoDoBancoDevolve422() throws Exception {
        when(service.registrar(anyLong(), any(), any(), any())).thenThrow(erroDoBanco(20003));

        enviar("k1", "{\"tipo\":\"DEBITO\",\"valor\":500}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("SALDO_INSUFICIENTE"));
    }

    @Test
    void chaveReutilizadaComOutroConteudoDevolve409() throws Exception {
        when(service.registrar(anyLong(), any(), any(), any())).thenThrow(erroDoBanco(20004));

        enviar("k1", "{\"tipo\":\"CREDITO\",\"valor\":50}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CHAVE_IDEMPOTENCIA_CONFLITO"));
    }

    @Test
    void carteiraInexistenteVindoDoBancoDevolve404() throws Exception {
        when(service.registrar(anyLong(), any(), any(), any())).thenThrow(erroDoBanco(20001));

        enviar("k1", "{\"tipo\":\"CREDITO\",\"valor\":10}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("CARTEIRA_NAO_ENCONTRADA"));
    }
}
