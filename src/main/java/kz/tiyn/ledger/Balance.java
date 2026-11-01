package kz.tiyn.ledger;

public record Balance(long balanceAmount, long lockedAmount, long availableAmount, String currency) {}
