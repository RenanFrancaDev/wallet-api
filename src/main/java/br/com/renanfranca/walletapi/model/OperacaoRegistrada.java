package br.com.renanfranca.walletapi.model;

/**
 * Resultado de registrar uma operação.
 *
 * @param lancamento o lançamento aplicado (ou, numa repetição, o original)
 * @param repetida {@code true} quando a mesma chave de idempotência já tinha sido aplicada com o
 *     mesmo conteúdo: nada mudou no saldo e o resultado devolvido é o da primeira chamada
 */
public record OperacaoRegistrada(Lancamento lancamento, boolean repetida) {}
