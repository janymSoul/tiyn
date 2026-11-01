package kz.tiyn.ledger.wallet.error;

public class LedgerException extends RuntimeException {

    private final LedgerError error;

    public LedgerException(LedgerError error) {
        super(error.code());
        this.error = error;
    }

    public LedgerError error() {
        return error;
    }
}
