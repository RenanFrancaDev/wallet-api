package br.com.renanfranca.walletapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** A configuração falha fechada: sem chave decente, a aplicação não sobe. */
class ApiKeyConfigTest {

    @ParameterizedTest(name = "chave \"{0}\" é recusada")
    @NullAndEmptySource
    @ValueSource(strings = {"curta", "123456789012345"})
    void chaveAusenteOuCurtaImpedeAInicializacao(String chave) {
        assertThatThrownBy(() -> new ApiKeyConfig(chave))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WALLET_API_KEY")
                .hasMessageContaining(String.valueOf(ApiKeyConfig.TAMANHO_MINIMO));
    }

    @Test
    void chaveComOTamanhoMinimoEAceita() {
        assertThatCode(() -> new ApiKeyConfig("1234567890123456")).doesNotThrowAnyException();
    }

    @Test
    void tamanhoMinimoEDe16Caracteres() {
        assertThat(ApiKeyConfig.TAMANHO_MINIMO).isEqualTo(16);
    }
}
