package br.com.renanfranca.walletapi.service;

import br.com.renanfranca.walletapi.model.OperacaoRegistrada;
import br.com.renanfranca.walletapi.model.TipoOperacao;
import br.com.renanfranca.walletapi.repository.LancamentoRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso de lançamentos. O {@code @Transactional} abre a transação, o PKG_WALLET trava a
 * carteira (e segura o lock até o fim) e o Spring faz o COMMIT ao sair: por isso a transação
 * precisa ser da aplicação, não da procedure.
 */
@Service
public class LancamentoService {

    private final LancamentoRepository repository;

    public LancamentoService(LancamentoRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public OperacaoRegistrada registrar(
            long carteiraId, TipoOperacao tipo, BigDecimal valor, String chave) {
        return repository.registrar(carteiraId, tipo, valor, chave);
    }
}
