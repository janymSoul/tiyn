package kz.tiyn.ledger.wallet.dto;

public record WalletExport(long balanceAmount, long lockedAmount, String currency) {}
