package br.com.renanfranca.walletapi.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.renanfranca.walletapi.exception.ErroNegocio;
import br.com.renanfranca.walletapi.exception.ErroNegocioException;
import br.com.renanfranca.walletapi.model.Carteira;
import br.com.renanfranca.walletapi.repository.CarteiraRepository;

/**
 * Casos de uso da carteira. E aqui que a TRANSACAO e controlada: o PKG_WALLET nao faz COMMIT,
 * entao o Spring abre a transacao ao entrar no metodo e confirma (ou desfaz, se lancar excecao) ao sair.
 *
 * <p>Analogia Node: {@code @Transactional} e o wrapper {@code db.transaction(async (tx) => { ... })}.
 *
 * <p>A dependencia ({@code repository}) chega pelo construtor: o container do Spring injeta,
 * como o NestJS faz com providers.
 */
@Service
public class CarteiraService {

    private final CarteiraRepository repository;

    public CarteiraService(CarteiraRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Carteira criar(String titular) {
        long id = repository.criar(titular);
        // Le na MESMA transacao: enxerga a carteira recem-criada, ainda nao confirmada.
        return repository.buscarPorId(id)
                .orElseThrow(() -> new IllegalStateException("Carteira recém-criada não encontrada: " + id));
    }

    @Transactional(readOnly = true)
    public Carteira buscar(long id) {
        return repository.buscarPorId(id)
                .orElseThrow(() -> new ErroNegocioException(ErroNegocio.CARTEIRA_NAO_ENCONTRADA));
    }
}
