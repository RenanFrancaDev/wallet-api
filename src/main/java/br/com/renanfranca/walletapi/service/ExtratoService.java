package br.com.renanfranca.walletapi.service;

import br.com.renanfranca.walletapi.exception.ErroNegocio;
import br.com.renanfranca.walletapi.exception.ErroNegocioException;
import br.com.renanfranca.walletapi.model.Lancamento;
import br.com.renanfranca.walletapi.model.Pagina;
import br.com.renanfranca.walletapi.repository.CarteiraRepository;
import br.com.renanfranca.walletapi.repository.LancamentoRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Consulta do extrato de uma carteira, com paginação e filtro por período. Só leitura. */
@Service
public class ExtratoService {

    static final int TAMANHO_MAXIMO = 100;

    private final CarteiraRepository carteiraRepository;
    private final LancamentoRepository lancamentoRepository;

    public ExtratoService(
            CarteiraRepository carteiraRepository, LancamentoRepository lancamentoRepository) {
        this.carteiraRepository = carteiraRepository;
        this.lancamentoRepository = lancamentoRepository;
    }

    /**
     * @param de início do período (inclusivo), ou nulo
     * @param ate fim do período (exclusivo), ou nulo
     * @param pagina número da página, começando em 0
     * @param tamanho itens por página, de 1 a {@value #TAMANHO_MAXIMO}
     */
    @Transactional(readOnly = true)
    public Pagina<Lancamento> consultar(
            long carteiraId, OffsetDateTime de, OffsetDateTime ate, int pagina, int tamanho) {
        validar(de, ate, pagina, tamanho);
        // Carteira inexistente é 404, e não uma lista vazia que esconderia o erro do cliente.
        carteiraRepository
                .buscarPorId(carteiraId)
                .orElseThrow(() -> new ErroNegocioException(ErroNegocio.CARTEIRA_NAO_ENCONTRADA));

        long total = lancamentoRepository.contar(carteiraId, de, ate);
        List<Lancamento> itens =
                total == 0
                        ? List.of()
                        : lancamentoRepository.listar(
                                carteiraId, de, ate, (long) pagina * tamanho, tamanho);
        return new Pagina<>(itens, pagina, tamanho, total);
    }

    private static void validar(OffsetDateTime de, OffsetDateTime ate, int pagina, int tamanho) {
        List<String> detalhes = new ArrayList<>();
        if (pagina < 0) {
            detalhes.add("page: deve ser maior ou igual a 0");
        }
        if (tamanho < 1 || tamanho > TAMANHO_MAXIMO) {
            detalhes.add("size: deve estar entre 1 e " + TAMANHO_MAXIMO);
        }
        if (de != null && ate != null && !de.isBefore(ate)) {
            detalhes.add("from: deve ser anterior a to");
        }
        if (!detalhes.isEmpty()) {
            throw new ErroNegocioException(ErroNegocio.DADOS_INVALIDOS, detalhes);
        }
    }
}
