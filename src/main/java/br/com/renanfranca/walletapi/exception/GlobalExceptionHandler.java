package br.com.renanfranca.walletapi.exception;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Traduz qualquer erro da aplicacao para o payload padrao ({@link ErroResponse}) e o status HTTP certo.
 *
 * <p>Analogia Node: e o middleware de erro do Express ({@code app.use((err, req, res, next) => ...)}),
 * so que aqui o Spring escolhe o metodo pelo TIPO da excecao.
 *
 * <p>Regra de seguranca: a mensagem do Oracle (stack, nomes de objetos) NUNCA vai para o cliente.
 * Erros de negocio saem com a mensagem do catalogo; erros inesperados saem genericos e o detalhe fica no log.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Primeira linha da mensagem de um erro de negocio do banco: "ORA-20002: Valor deve ser maior que zero." */
    private static final Pattern MENSAGEM_ORA_NEGOCIO = Pattern.compile("ORA-20\\d{3}:\\s*(.+)");

    /** Erro de negocio detectado pela aplicacao. */
    @ExceptionHandler(ErroNegocioException.class)
    public ResponseEntity<ErroResponse> negocio(ErroNegocioException ex, HttpServletRequest request) {
        return resposta(ex.getErro(), List.of(), request);
    }

    /** Erro vindo do banco: ORA-20001..20006 viram erros de negocio; o resto e erro interno. */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErroResponse> banco(DataAccessException ex, HttpServletRequest request) {
        SQLException sql = encontrarSqlException(ex);
        if (sql != null) {
            Optional<ErroNegocio> erro = ErroNegocio.deCodigoOracle(sql.getErrorCode());
            if (erro.isPresent()) {
                return resposta(erro.get(), detalhesDoBanco(erro.get(), sql), request);
            }
        }
        log.error("Erro de acesso a dados em {}", request.getRequestURI(), ex);
        return erroInterno(request);
    }

    /** Falha do Bean Validation no corpo da requisicao (ex.: titular com mais de 120 caracteres). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErroResponse> validacao(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> detalhes = ex.getBindingResult().getFieldErrors().stream()
                .map(campo -> campo.getField() + ": " + campo.getDefaultMessage())
                .toList();
        return resposta(ErroNegocio.DADOS_INVALIDOS, detalhes, request);
    }

    /** Corpo ausente ou JSON malformado. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErroResponse> corpoIlegivel(HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        return resposta(ErroNegocio.DADOS_INVALIDOS, List.of("Corpo da requisição ausente ou malformado."), request);
    }

    /** Parametro de rota com tipo errado (ex.: /wallets/abc). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErroResponse> tipoDoParametro(MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {
        return resposta(ErroNegocio.DADOS_INVALIDOS, List.of(ex.getName() + ": valor inválido"), request);
    }

    /**
     * Rede de seguranca. Erros do proprio Spring MVC (rota inexistente, metodo nao permitido...) mantem o status
     * deles, mas no nosso formato; qualquer outra coisa e 500 sem expor detalhes.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErroResponse> inesperado(Exception ex, HttpServletRequest request) {
        if (ex instanceof org.springframework.web.ErrorResponse doSpring) {
            HttpStatusCode status = doSpring.getStatusCode();
            HttpStatus conhecido = HttpStatus.resolve(status.value());
            String codigo = conhecido != null ? conhecido.name() : "REQUISICAO_INVALIDA";
            return ResponseEntity.status(status).body(corpo(codigo, mensagemParaStatus(status), request, List.of()));
        }
        log.error("Erro inesperado em {}", request.getRequestURI(), ex);
        return erroInterno(request);
    }

    private static ResponseEntity<ErroResponse> resposta(ErroNegocio erro, List<String> detalhes,
            HttpServletRequest request) {
        return ResponseEntity.status(erro.status())
                .body(corpo(erro.name(), erro.mensagem(), request, detalhes));
    }

    private static ResponseEntity<ErroResponse> erroInterno(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(corpo("ERRO_INTERNO", "Erro interno. Tente novamente mais tarde.", request, List.of()));
    }

    private static ErroResponse corpo(String codigo, String mensagem, HttpServletRequest request,
            List<String> detalhes) {
        return new ErroResponse(codigo, mensagem, OffsetDateTime.now(), request.getRequestURI(), detalhes);
    }

    private static String mensagemParaStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> "Recurso não encontrado.";
            case 405 -> "Método HTTP não permitido para este recurso.";
            case 415 -> "Tipo de conteúdo não suportado.";
            default -> "Requisição inválida.";
        };
    }

    /** Procura, na cadeia de causas, a SQLException que o driver do Oracle lancou. */
    private static SQLException encontrarSqlException(Throwable erro) {
        for (Throwable atual = erro; atual != null; atual = atual.getCause()) {
            if (atual instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }

    /**
     * Para DADOS_INVALIDOS o motivo exato (qual regra falhou) esta na mensagem que NOS escrevemos no PL/SQL,
     * entao e seguro e util devolve-la. Para os demais, a mensagem do catalogo basta.
     */
    private static List<String> detalhesDoBanco(ErroNegocio erro, SQLException sql) {
        if (erro != ErroNegocio.DADOS_INVALIDOS || sql.getMessage() == null) {
            return List.of();
        }
        Matcher encontrado = MENSAGEM_ORA_NEGOCIO.matcher(sql.getMessage());
        return encontrado.find() ? List.of(encontrado.group(1).trim()) : List.of();
    }
}
