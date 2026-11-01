package kz.tiyn.ledger.wallet.dto;

public record BalanceResponse(long approvedAmount, long lockedAmount, long availableAmount, String currency) {}
