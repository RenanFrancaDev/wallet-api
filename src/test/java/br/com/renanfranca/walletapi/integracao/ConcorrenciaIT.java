package br.com.renanfranca.walletapi.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.renanfranca.walletapi.integracao.ClienteHttp.Resposta;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * O CORAÇÃO do desafio: várias operações sobre a mesma carteira ao mesmo tempo, por HTTP real,
 * contra o Oracle real. Prova as três garantias do enunciado: (a) o saldo nunca fica negativo, (b)
 * nenhuma operação é aplicada em dobro e (c) o saldo final é a soma consistente do que foi
 * efetivamente aplicado.
 *
 * <p>Diferente de disparar {@code curl} em segundo plano, aqui as threads esperam numa
 * <b>barreira</b> e são soltas no MESMO instante ({@link CountDownLatch}), o que maximiza a
 * colisão. A verdade final é conferida DIRETO no banco (saldo, extrato e trilha de auditoria), não
 * pelo que a API diz.
 *
 * <p>Analogia Node: um {@code Promise.all} de N {@code fetch} liberados juntos, só que com threads
 * de verdade.
 */
class ConcorrenciaIT extends OracleIntegracaoBase {

    private ClienteHttp api;

    @BeforeEach
    void prepararCliente() {
        api = cliente();
    }

    /** Uma chamada que o teste dispara numa thread; recebe o índice da thread. */
    @FunctionalInterface
    private interface Chamada {
        Resposta executar(int indice) throws Exception;
    }

    /**
     * Dispara {@code quantas} chamadas ao mesmo tempo: todas as threads ficam prontas, esperam a
     * largada e são soltas juntas.
     */
    private static List<Resposta> simultaneas(int quantas, Chamada chamada) throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(quantas);
        try {
            CountDownLatch prontas = new CountDownLatch(quantas);
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<Resposta>> futuros = new ArrayList<>();
            for (int i = 0; i < quantas; i++) {
                int indice = i;
                Callable<Resposta> tarefa =
                        () -> {
                            prontas.countDown();
                            largada.await();
                            return chamada.executar(indice);
                        };
                futuros.add(threads.submit(tarefa));
            }
            assertThat(prontas.await(30, TimeUnit.SECONDS)).as("todas as threads prontas").isTrue();
            largada.countDown();

            List<Resposta> respostas = new ArrayList<>();
            for (Future<Resposta> futuro : futuros) {
                respostas.add(futuro.get(120, TimeUnit.SECONDS));
            }
            return respostas;
        } finally {
            threads.shutdownNow();
        }
    }

    private static long quantas(List<Resposta> respostas, int status) {
        return respostas.stream().filter(r -> r.status() == status).count();
    }

    private static long repeticoes(List<Resposta> respostas) {
        return respostas.stream()
                .filter(r -> "true".equals(r.cabecalho("Idempotent-Replayed")))
                .count();
    }

    private static String rota(long carteira) {
        return "/wallets/" + carteira + "/transactions";
    }

    private static String corpo(String tipo, String valor) {
        return "{\"tipo\":\"" + tipo + "\",\"valor\":" + valor + "}";
    }

    /** Carteira nova com um crédito inicial (se maior que zero). */
    private long carteiraComSaldo(String saldoInicial) {
        long carteira = ((Number) api.post("/wallets").json("$.id")).longValue();
        if (!"0".equals(saldoInicial)) {
            Resposta carga =
                    api.post(rota(carteira), "carga-inicial", corpo("CREDITO", saldoInicial));
            assertThat(carga.status()).isEqualTo(201);
        }
        return carteira;
    }

    /**
     * As invariantes que valem para QUALQUER sequência de operações, conferidas direto no banco:
     * saldo nunca negativo, saldo igual à soma do extrato e ao saldo do último lançamento, e trilha
     * de auditoria completa e encadeada.
     */
    private static void confereInvariantes(long carteira, long lancamentosEsperados)
            throws SQLException {
        assertThat(ConferenciaDoBanco.saldo(carteira))
                .as("saldo nunca negativo")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(ConferenciaDoBanco.saldo(carteira))
                .as("saldo = soma do extrato")
                .isEqualByComparingTo(ConferenciaDoBanco.somaDoExtrato(carteira));
        assertThat(ConferenciaDoBanco.saldo(carteira))
                .as("saldo = saldo do último lançamento")
                .isEqualByComparingTo(ConferenciaDoBanco.saldoDoUltimoLancamento(carteira));
        assertThat(ConferenciaDoBanco.lancamentos(carteira))
                .as("lançamentos no extrato")
                .isEqualTo(lancamentosEsperados);
        assertThat(ConferenciaDoBanco.linhasDeAuditoria(carteira))
                .as("uma linha de auditoria por mudança de saldo")
                .isEqualTo(lancamentosEsperados);
        assertThat(ConferenciaDoBanco.quebrasNaCadeiaDaAuditoria(carteira))
                .as("quebras na cadeia da auditoria")
                .isZero();
    }

    // ------------------------------------------------------------------------------------------
    // (a) saldo

    @Test
    void trintaDebitosSimultaneosSobreSaldo100AplicamExatamenteDezENuncaDeixamOSaldoNegativo()
            throws Exception {
        long carteira = carteiraComSaldo("100");

        List<Resposta> respostas =
                simultaneas(
                        30, i -> api.post(rota(carteira), "debito-" + i, corpo("DEBITO", "10")));

        assertThat(quantas(respostas, 201)).as("débitos aplicados").isEqualTo(10);
        assertThat(quantas(respostas, 422))
                .as("débitos recusados por saldo insuficiente")
                .isEqualTo(20);
        assertThat(ConferenciaDoBanco.saldo(carteira)).isEqualByComparingTo("0");
        confereInvariantes(carteira, 11); // 1 crédito inicial + 10 débitos
    }

    // ------------------------------------------------------------------------------------------
    // (b) em dobro

    @Test
    void vinteChamadasIguaisSimultaneasAplicamUmaVezSoEAsDemaisRecebemOResultadoOriginal()
            throws Exception {
        long carteira = carteiraComSaldo("0");

        List<Resposta> respostas =
                simultaneas(
                        20, i -> api.post(rota(carteira), "mesma-chave", corpo("CREDITO", "25")));

        assertThat(quantas(respostas, 201)).as("todas respondem 201").isEqualTo(20);
        assertThat(repeticoes(respostas)).as("repetições (com Idempotent-Replayed)").isEqualTo(19);
        // As 20 respostas são o MESMO lançamento: o resultado da primeira chamada.
        assertThat(
                        respostas.stream()
                                .map(r -> ((Number) r.json("$.id")).longValue())
                                .distinct()
                                .count())
                .as("lançamentos distintos devolvidos")
                .isEqualTo(1);
        assertThat(ConferenciaDoBanco.saldo(carteira))
                .as("25, e não 500")
                .isEqualByComparingTo("25");
        confereInvariantes(carteira, 1);
    }

    @Test
    void mesmaChaveComConteudosDiferentesSimultaneosSoUmConteudoVence() throws Exception {
        long carteira = carteiraComSaldo("100");

        List<Resposta> respostas =
                simultaneas(
                        20,
                        i ->
                                api.post(
                                        rota(carteira),
                                        "chave-disputada",
                                        corpo(i % 2 == 0 ? "CREDITO" : "DEBITO", "10")));

        assertThat(quantas(respostas, 201) + quantas(respostas, 409))
                .as("só 201 ou 409")
                .isEqualTo(20);
        assertThat(quantas(respostas, 201)).as("o conteúdo vencedor").isEqualTo(10);
        assertThat(quantas(respostas, 409)).as("o conteúdo perdedor").isEqualTo(10);
        // Todas as respostas 201 são o mesmo lançamento (o vencedor), uma nova e as outras
        // repetições.
        assertThat(
                        respostas.stream()
                                .filter(r -> r.status() == 201)
                                .map(r -> ((Number) r.json("$.id")).longValue())
                                .distinct()
                                .count())
                .isEqualTo(1);
        assertThat(ConferenciaDoBanco.saldo(carteira).intValue())
                .as("110 ou 90, nunca 100")
                .isIn(90, 110);
        confereInvariantes(carteira, 2); // carga inicial + o vencedor
    }

    // ------------------------------------------------------------------------------------------
    // (c) soma

    @Test
    void creditosEDebitosMisturadosSimultaneosMantemAsInvariantes() throws Exception {
        long carteira = carteiraComSaldo("100");

        List<Resposta> respostas =
                simultaneas(
                        40,
                        i ->
                                api.post(
                                        rota(carteira),
                                        "misto-" + i,
                                        corpo(i % 2 == 0 ? "CREDITO" : "DEBITO", "10")));

        // Os números exatos dependem da ordem em que a fila do lock decide; as invariantes não.
        assertThat(quantas(respostas, 201) + quantas(respostas, 422))
                .as("só 201 ou 422")
                .isEqualTo(40);
        long aplicados = quantas(respostas, 201);
        confereInvariantes(carteira, 1 + aplicados);
    }

    @Test
    void operacoesSimultaneasEmVariasCarteirasNaoSeInterferem() throws Exception {
        int carteiras = 5;
        int operacoesPorCarteira = 10;
        List<Long> ids = new ArrayList<>();
        for (int c = 0; c < carteiras; c++) {
            ids.add(carteiraComSaldo("0"));
        }

        IntFunction<Long> carteiraDaThread = i -> ids.get(i % carteiras);
        List<Resposta> respostas =
                simultaneas(
                        carteiras * operacoesPorCarteira,
                        i ->
                                api.post(
                                        rota(carteiraDaThread.apply(i)),
                                        "multi-" + i,
                                        corpo("CREDITO", "10")));

        assertThat(quantas(respostas, 201))
                .as("todas aplicadas")
                .isEqualTo((long) carteiras * operacoesPorCarteira);
        for (long carteira : ids) {
            assertThat(ConferenciaDoBanco.saldo(carteira))
                    .as("saldo da carteira " + carteira)
                    .isEqualByComparingTo("100");
            confereInvariantes(carteira, operacoesPorCarteira);
        }
    }
}
