-- Executada pelo Flyway conectado como WALLET_OWNER.
-- Privilegio minimo do usuario da aplicacao: executar o package e ler as views.
-- Nenhum INSERT, UPDATE ou DELETE em tabela. Sem acesso ao schema de auditoria.
-- O PKG_WALLET roda com os direitos do dono (AUTHID DEFINER), por isso o APP nao precisa
-- de acesso as tabelas nem ao PKG_WALLET_CTX.

GRANT EXECUTE ON PKG_WALLET TO WALLET_APP;
GRANT SELECT  ON V_CARTEIRA TO WALLET_APP;
GRANT SELECT  ON V_EXTRATO  TO WALLET_APP;
