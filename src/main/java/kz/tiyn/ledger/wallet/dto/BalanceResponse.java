package kz.tiyn.ledger.wallet.dto;

public record BalanceResponse(long balanceAmount, long lockedAmount, long availableAmount, String currency) {}
