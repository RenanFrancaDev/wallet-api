package br.com.renanfranca.walletapi.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.renanfranca.walletapi.integracao.ClienteHttp.Resposta;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A API de ponta a ponta, por HTTP real, contra o Oracle real: o que um cliente de verdade vê. É o
 * roteiro que antes eu rodava à mão com curl, agora automático no CI.
 */
class OperacoesIT extends OracleIntegracaoBase {

    private ClienteHttp api;

    /**
     * Não pode ser um inicializador de campo: a porta só é injetada DEPOIS de o objeto ser
     * construído.
     */
    @BeforeEach
    void prepararCliente() {
        api = cliente();
    }

    private long novaCarteira() {
        Resposta criada = api.post("/wallets");
        assertThat(criada.status()).isEqualTo(201);
        return ((Number) criada.json("$.id")).longValue();
    }

    private static String rota(long carteira) {
        return "/wallets/" + carteira + "/transactions";
    }

    private static String corpo(String tipo, String valor) {
        return "{\"tipo\":\"" + tipo + "\",\"valor\":" + valor + "}";
    }

    @Test
    void criarEConsultarCarteiraComSaldoZeroComDuasCasas() {
        Resposta criada = api.post("/wallets");

        assertThat(criada.status()).isEqualTo(201);
        assertThat(criada.cabecalho("Location")).endsWith("/wallets/" + criada.json("$.id"));
        assertThat(criada.corpo()).contains("\"saldo\":0.00");

        Resposta consultada = api.get("/wallets/" + criada.json("$.id"));
        assertThat(consultada.status()).isEqualTo(200);
        assertThat(consultada.corpo()).contains("\"saldo\":0.00");
    }

    @Test
    void creditoRepeticaoConflitoDebitoESaldoInsuficiente() throws SQLException {
        long carteira = novaCarteira();

        Resposta credito = api.post(rota(carteira), "k1", corpo("CREDITO", "100.00"));
        assertThat(credito.status()).isEqualTo(201);
        assertThat(credito.corpo()).contains("\"saldoApos\":100.00");
        assertThat(credito.cabecalho("Idempotent-Replayed")).isNull();

        // Mesma chave e mesmo conteúdo: o MESMO resultado, marcado como repetição, sem mexer no
        // saldo.
        Resposta repeticao = api.post(rota(carteira), "k1", corpo("CREDITO", "100.00"));
        assertThat(repeticao.status()).isEqualTo(201);
        assertThat(repeticao.cabecalho("Idempotent-Replayed")).isEqualTo("true");
        assertThat((Number) repeticao.json("$.id")).isEqualTo(credito.json("$.id"));
        assertThat(api.get("/wallets/" + carteira).corpo()).contains("\"saldo\":100.00");

        // Mesma chave com outro conteúdo: conflito.
        Resposta conflito = api.post(rota(carteira), "k1", corpo("CREDITO", "50.00"));
        assertThat(conflito.status()).isEqualTo(409);
        assertThat((String) conflito.json("$.codigo")).isEqualTo("CHAVE_IDEMPOTENCIA_CONFLITO");

        Resposta debito = api.post(rota(carteira), "d1", corpo("DEBITO", "30.00"));
        assertThat(debito.status()).isEqualTo(201);
        assertThat(debito.corpo()).contains("\"saldoApos\":70.00");

        // Débito maior que o saldo: recusado, sem alterar nada.
        Resposta semSaldo = api.post(rota(carteira), "d2", corpo("DEBITO", "500"));
        assertThat(semSaldo.status()).isEqualTo(422);
        assertThat((String) semSaldo.json("$.codigo")).isEqualTo("SALDO_INSUFICIENTE");
        assertThat(ConferenciaDoBanco.saldo(carteira)).isEqualByComparingTo("70");
        // O extrato só tem o que foi aplicado: a repetição, o conflito e a recusa não criam linha.
        assertThat(ConferenciaDoBanco.lancamentos(carteira)).isEqualTo(2);
        assertThat(ConferenciaDoBanco.somaDoExtrato(carteira)).isEqualByComparingTo("70");
    }

    @Test
    void extratoPaginadoDoMaisRecenteParaOMaisAntigoComFiltroDePeriodo() {
        long carteira = novaCarteira();
        for (int i = 1; i <= 5; i++) {
            api.post(rota(carteira), "c" + i, corpo("CREDITO", "10"));
        }

        Resposta pagina0 = api.get(rota(carteira) + "?page=0&size=2");
        assertThat(pagina0.status()).isEqualTo(200);
        assertThat((Number) pagina0.json("$.totalItens")).isEqualTo(5);
        assertThat((Number) pagina0.json("$.totalPaginas")).isEqualTo(3);
        // Do mais recente para o mais antigo: saldos 50 e 40 na primeira página.
        assertThat(pagina0.corpo()).contains("\"saldoApos\":50.00").contains("\"saldoApos\":40.00");

        Resposta ultima = api.get(rota(carteira) + "?page=2&size=2");
        assertThat((Number) ultima.json("$.itens.length()")).isEqualTo(1);

        Resposta alemDoFim = api.get(rota(carteira) + "?page=9&size=2");
        assertThat((Number) alemDoFim.json("$.itens.length()")).isEqualTo(0);

        Resposta periodoVazio =
                api.get(rota(carteira) + "?from=2020-01-01T00:00:00Z&to=2020-02-01T00:00:00Z");
        assertThat((Number) periodoVazio.json("$.totalItens")).isEqualTo(0);
    }

    @Test
    void dadosInvalidosDevolvem400ComOMotivo() {
        long carteira = novaCarteira();

        assertThat(api.post(rota(carteira), "x1", corpo("CREDITO", "0")).status()).isEqualTo(400);
        assertThat(api.post(rota(carteira), "x2", corpo("CREDITO", "10.005")).status())
                .isEqualTo(400);
        assertThat(api.post(rota(carteira), "x3", corpo("PIX", "10")).status()).isEqualTo(400);
        // Sem Idempotency-Key.
        Resposta semChave = api.post(rota(carteira), null, corpo("CREDITO", "10"));
        assertThat(semChave.status()).isEqualTo(400);
        assertThat((String) semChave.json("$.codigo")).isEqualTo("DADOS_INVALIDOS");
        // Chave com 101 caracteres: a regra é do banco, que devolve o motivo.
        Resposta chaveLonga = api.post(rota(carteira), "k".repeat(101), corpo("CREDITO", "10"));
        assertThat(chaveLonga.status()).isEqualTo(400);
        assertThat(chaveLonga.corpo()).contains("excede 100 caracteres");

        assertThat(api.get(rota(carteira) + "?size=0").status()).isEqualTo(400);
        assertThat(api.get(rota(carteira) + "?from=ontem").status()).isEqualTo(400);
    }

    @Test
    void carteiraInexistenteDevolve404() {
        assertThat(api.get("/wallets/999999999").status()).isEqualTo(404);
        assertThat(api.post(rota(999999999), "n1", corpo("CREDITO", "10")).status()).isEqualTo(404);
        assertThat(api.get(rota(999999999)).status()).isEqualTo(404);
    }

    @Test
    void semAApiKeyTodasAsRotasDevolvem401MasASaudeEPublica() {
        ClienteHttp semChave = clienteSemChave();

        assertThat(semChave.get("/wallets/1").status()).isEqualTo(401);
        assertThat(semChave.post("/wallets").status()).isEqualTo(401);
        assertThat(semChave.get("/nao-existe").status()).isEqualTo(401);
        Resposta negada = semChave.get("/wallets/1");
        assertThat((String) negada.json("$.codigo")).isEqualTo("NAO_AUTORIZADO");

        Resposta saude = semChave.get("/actuator/health");
        assertThat(saude.status()).isEqualTo(200);
        assertThat(saude.corpo()).contains("UP");

        assertThat(new ClienteHttp(porta, "chave-errada-0123456789").get("/wallets/1").status())
                .isEqualTo(401);
    }
}
