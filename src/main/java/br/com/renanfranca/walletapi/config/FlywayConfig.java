package br.com.renanfranca.walletapi.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Executa as migrations do banco ao iniciar a aplicacao, em DOIS conjuntos, cada um com o seu usuario.
 *
 * <p>Analogia Node: e o "drizzle-kit migrate" rodando antes do servidor subir, so que duas vezes,
 * com credenciais diferentes. A ordem importa: a trigger do OWNER chama uma procedure do schema de
 * auditoria, entao o conjunto audit precisa existir primeiro.
 *
 * <p>A aplicacao em si conecta como WALLET_APP (ver application.properties) e nunca usa estas credenciais
 * para servir requisicoes: elas existem so para criar/atualizar o banco no startup.
 */
@Configuration
public class FlywayConfig {

    /** Primeiro: schema de auditoria, executado como WALLET_AUDIT. */
    @Bean(initMethod = "migrate")
    public Flyway flywayAudit(
            @Value("${spring.datasource.url}") String url,
            @Value("${wallet.flyway.audit-user}") String usuario,
            @Value("${wallet.flyway.audit-password}") String senha) {
        return flyway(url, usuario, senha, "classpath:db/migration/audit");
    }

    /** Depois: schema do dono, executado como WALLET_OWNER. */
    @Bean(initMethod = "migrate")
    @DependsOn("flywayAudit")
    public Flyway flywayOwner(
            @Value("${spring.datasource.url}") String url,
            @Value("${wallet.flyway.owner-user}") String usuario,
            @Value("${wallet.flyway.owner-password}") String senha) {
        return flyway(url, usuario, senha, "classpath:db/migration/owner");
    }

    private static Flyway flyway(String url, String usuario, String senha, String locations) {
        // Entregamos ao Flyway um DataSource pronto em vez de url/usuario/senha. Quando o Flyway monta a
        // conexao sozinha, o modulo Oracle procura um arquivo de log dentro do jar do driver e converte o
        // caminho "nested:C:\..." com Paths.get(), o que falha no Windows rodando o jar executavel do
        // Spring Boot (InvalidPathException). Com DataSource proprio esse trecho nao e executado.
        // Nao precisa de pool: a migration roda uma unica vez no startup.
        DataSource dataSource = new DriverManagerDataSource(url, usuario, senha);
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(locations)
                .load();
    }
}
