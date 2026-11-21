package br.com.renanfranca.walletapi.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;

/**
 * Traducao de erros: o foco do enunciado ("testes unitarios cobrindo a traducao de erros e saldo
 * insuficiente"). Sem Spring e sem banco: instancia o handler e passa excecoes montadas a mao, como
 * o driver do Oracle as lanca.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request =
            new MockHttpServletRequest("POST", "/wallets/1/transactions");

    /**
     * Excecao como o Spring a entrega: DataAccessException embrulhando a SQLException do driver.
     */
    private static DataAccessException erroDoBanco(int codigoOra, String mensagem) {
        SQLException sql = new SQLException(mensagem, "72000", codigoOra);
        return new UncategorizedSQLException(
                "CallableStatementCallback", "{call PKG_WALLET...}", sql);
    }

    @Test
    void saldoInsuficienteViraHttp422ComCodigoEMensagemDoCatalogo() {
        DataAccessException erro =
                erroDoBanco(
                        20003,
                        "ORA-20003: Saldo insuficiente.\nORA-06512: at \"WALLET_OWNER.PKG_WALLET\", line 94");

        ResponseEntity<ErroResponse> resposta = handler.banco(erro, request);

        assertThat(resposta.getStatusCode().value()).isEqualTo(422);
        ErroResponse corpo = resposta.getBody();
        assertThat(corpo).isNotNull();
        assertThat(corpo.codigo()).isEqualTo("SALDO_INSUFICIENTE");
        assertThat(corpo.mensagem()).isEqualTo(ErroNegocio.SALDO_INSUFICIENTE.mensagem());
        assertThat(corpo.path()).isEqualTo("/wallets/1/transactions");
        assertThat(corpo.timestamp()).isNotNull();
        assertThat(corpo.detalhes()).isEmpty();
    }

    @ParameterizedTest(name = "ORA-{0} -> HTTP {1} {2}")
    @CsvSource({
        "20001, 404, CARTEIRA_NAO_ENCONTRADA",
        "20002, 400, DADOS_INVALIDOS",
        "20003, 422, SALDO_INSUFICIENTE",
        "20004, 409, CHAVE_IDEMPOTENCIA_CONFLITO",
        "20005, 409, EXTRATO_IMUTAVEL",
        "20006, 503, CARTEIRA_OCUPADA"
    })
    void cadaCodigoDoBancoViraOStatusECodigoEsperados(int codigoOra, int status, String codigo) {
        ResponseEntity<ErroResponse> resposta =
                handler.banco(
                        erroDoBanco(codigoOra, "ORA-" + codigoOra + ": mensagem do banco"),
                        request);

        assertThat(resposta.getStatusCode().value()).isEqualTo(status);
        assertThat(resposta.getBody().codigo()).isEqualTo(codigo);
    }

    @Test
    void dadosInvalidosDevolveOMotivoEscritoNoPlsql() {
        DataAccessException erro =
                erroDoBanco(
                        20002,
                        "ORA-20002: Valor deve ser maior que zero.\nORA-06512: at \"WALLET_OWNER.PKG_WALLET\", line 70");

        ErroResponse corpo = handler.banco(erro, request).getBody();

        assertThat(corpo.detalhes()).containsExactly("Valor deve ser maior que zero.");
    }

    @Test
    void erroDeTriggerComCadeiaDeMensagensTambemETraduzido() {
        // Quando a trigger lanca, o Oracle encadeia ORA-20005 + ORA-06512 + ORA-04088; o
        // getErrorCode e o do primeiro.
        DataAccessException erro =
                erroDoBanco(
                        20005,
                        "ORA-20005: Lancamento e imutavel.\nORA-06512: at \"WALLET_OWNER.TRG_LANCAMENTO_IMUTAVEL\", line 2\n"
                                + "ORA-04088: error during execution of trigger");

        assertThat(handler.banco(erro, request).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void encontraASqlExceptionMesmoEmCausaAninhada() {
        SQLException sql = new SQLException("ORA-20004: chave", "72000", 20004);
        DataAccessException erro =
                new DataIntegrityViolationException("falha", new RuntimeException(sql));

        ResponseEntity<ErroResponse> resposta = handler.banco(erro, request);

        assertThat(resposta.getStatusCode().value()).isEqualTo(409);
        assertThat(resposta.getBody().codigo()).isEqualTo("CHAVE_IDEMPOTENCIA_CONFLITO");
    }

    @Test
    void erroDoBancoQueNaoENegocioViraHttp500SemVazarDetalhes() {
        DataAccessException erro =
                erroDoBanco(
                        942,
                        "ORA-00942: table or view \"WALLET_OWNER\".\"CARTEIRA\" does not exist");

        ResponseEntity<ErroResponse> resposta = handler.banco(erro, request);

        assertThat(resposta.getStatusCode().value()).isEqualTo(500);
        ErroResponse corpo = resposta.getBody();
        assertThat(corpo.codigo()).isEqualTo("ERRO_INTERNO");
        assertThat(corpo.mensagem()).doesNotContain("ORA-").doesNotContain("CARTEIRA");
        assertThat(corpo.detalhes()).isEmpty();
    }

    @Test
    void erroDeNegocioDaAplicacaoUsaOMesmoFormato() {
        ResponseEntity<ErroResponse> resposta =
                handler.negocio(
                        new ErroNegocioException(ErroNegocio.CARTEIRA_NAO_ENCONTRADA), request);

        assertThat(resposta.getStatusCode().value()).isEqualTo(404);
        assertThat(resposta.getBody().codigo()).isEqualTo("CARTEIRA_NAO_ENCONTRADA");
    }

    @Test
    void excecaoInesperadaViraHttp500SemVazarAMensagem() {
        ResponseEntity<ErroResponse> resposta =
                handler.inesperado(new RuntimeException("segredo interno"), request);

        assertThat(resposta.getStatusCode().value()).isEqualTo(500);
        assertThat(resposta.getBody().mensagem()).doesNotContain("segredo");
    }

    @Test
    void erroDoPrimeiroSpringMvcMantemOStatusMasNoNossoFormato() {
        ResponseEntity<ErroResponse> resposta =
                handler.inesperado(new HttpRequestMethodNotSupportedException("PUT"), request);

        assertThat(resposta.getStatusCode().value()).isEqualTo(405);
        assertThat(resposta.getBody().codigo()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(resposta.getBody().path()).isEqualTo("/wallets/1/transactions");
    }
}
