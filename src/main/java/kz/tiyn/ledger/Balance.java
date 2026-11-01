package kz.tiyn.ledger;

public record Balance(long approvedAmount, long lockedAmount, long availableAmount, String currency) {}
