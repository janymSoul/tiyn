package kz.tiyn.ledger.wallet.error;

public enum LedgerError {
    FORBIDDEN,
    INVALID_AMOUNT,
    UNSUPPORTED_CURRENCY;

    public String code() {
        return name().toLowerCase();
    }
}
