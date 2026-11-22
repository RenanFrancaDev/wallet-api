package br.com.renanfranca.walletapi.integracao;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base dos testes de integração: sobe UM Oracle de verdade (Testcontainers) para todas as classes
 * {@code *IT} e a aplicação inteira conectada nele.
 *
 * <p>Analogia Node: é o {@code testcontainers} do Node subindo um Postgres descartável, com o
 * servidor Express rodando contra ele.
 *
 * <p>O que torna este teste fiel à produção:
 *
 * <ul>
 *   <li>A imagem é a MESMA do {@code docker-compose.yml}, com tag fixa (nunca {@code latest}).
 *   <li>Os três usuários (OWNER, APP e AUDIT) são criados pelo MESMO script de init do compose
 *       ({@code db/init/01_usuarios.sh}), não por um atalho do teste: se o script quebrar, o teste
 *       quebra.
 *   <li>As migrations rodam pelo {@code FlywayConfig} da aplicação, como no startup real. A
 *       aplicação conecta como WALLET_APP, o usuário de privilégio mínimo.
 * </ul>
 *
 * <p>Container único (padrão "singleton"): sobe uma vez e é reaproveitado por todas as classes. O
 * Testcontainers o derruba ao fim da execução. As senhas abaixo são descartáveis: existem só dentro
 * deste container de teste.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class OracleIntegracaoBase {

    /** Mantenha igual à imagem do docker-compose.yml. */
    static final String IMAGEM = "gvenzl/oracle-free:23.26.4-slim-faststart";

    static final String SENHA_OWNER = "owner_integracao_1";
    static final String SENHA_APP = "app_integracao_1";
    static final String SENHA_AUDIT = "audit_integracao_1";
    static final String CHAVE_API = "chave-de-integracao-123456";

    @SuppressWarnings(
            "resource") // container único e estático: o Testcontainers o encerra ao fim da execução
    static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse(IMAGEM))
                    // O padrão do Testcontainers é ~1 min; o Oracle Free leva ~2 min para ficar
                    // pronto em
                    // máquinas modestas. É só um teto: o teste segue assim que o banco responde.
                    .withStartupTimeout(Duration.ofMinutes(5))
                    .withEnv("WALLET_OWNER_PASSWORD", SENHA_OWNER)
                    .withEnv("WALLET_APP_PASSWORD", SENHA_APP)
                    .withEnv("WALLET_AUDIT_PASSWORD", SENHA_AUDIT)
                    // 0755: o script precisa ser executável dentro do container.
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(
                                    Path.of("db/init/01_usuarios.sh").toAbsolutePath(), 0755),
                            "/container-entrypoint-initdb.d/01_usuarios.sh");

    static {
        ORACLE.start();
    }

    /**
     * Aponta a aplicação para o container. Os nomes {@code WALLET_*} são os mesmos placeholders do
     * {@code application.properties}, que a aplicação normalmente lê do {@code .env}: aqui o teste
     * os fornece, e uma propriedade dinâmica tem precedência sobre qualquer {@code .env} que exista
     * na máquina.
     */
    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", ORACLE::getJdbcUrl);
        registro.add("WALLET_OWNER_PASSWORD", () -> SENHA_OWNER);
        registro.add("WALLET_APP_PASSWORD", () -> SENHA_APP);
        registro.add("WALLET_AUDIT_PASSWORD", () -> SENHA_AUDIT);
        registro.add("WALLET_API_KEY", () -> CHAVE_API);
    }

    /**
     * Conexão JDBC direta, como o usuário informado, para conferir o estado e os privilégios do
     * banco.
     */
    static Connection conectar(String usuario, String senha) throws SQLException {
        Connection conexao = DriverManager.getConnection(ORACLE.getJdbcUrl(), usuario, senha);
        // Sem auto-commit: cada teste confirma explicitamente (o driver Oracle recusa commit() com
        // auto-commit ligado).
        conexao.setAutoCommit(false);
        return conexao;
    }

    static Connection comoOwner() throws SQLException {
        return conectar("WALLET_OWNER", SENHA_OWNER);
    }

    static Connection comoApp() throws SQLException {
        return conectar("WALLET_APP", SENHA_APP);
    }

    static Connection comoAudit() throws SQLException {
        return conectar("WALLET_AUDIT", SENHA_AUDIT);
    }

    /** Executa um SQL (DML ou bloco PL/SQL) e confirma. */
    static void executar(Connection conexao, String sql) throws SQLException {
        try (PreparedStatement comando = conexao.prepareStatement(sql)) {
            comando.execute();
        }
        conexao.commit();
    }

    /** Lê um único número (COUNT, SUM, ID...). */
    static long numero(Connection conexao, String sql) throws SQLException {
        try (PreparedStatement comando = conexao.prepareStatement(sql);
                ResultSet resultado = comando.executeQuery()) {
            resultado.next();
            return resultado.getLong(1);
        }
    }
}
