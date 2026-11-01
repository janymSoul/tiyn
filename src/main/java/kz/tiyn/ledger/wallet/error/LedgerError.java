package kz.tiyn.ledger.wallet.error;

public enum LedgerError {
    FORBIDDEN,
    NOT_FOUND,
    INVALID_AMOUNT,
    UNSUPPORTED_CURRENCY,
    AMOUNT_TOO_SMALL,
    INSUFFICIENT_FUNDS,
    PARENT_REQUIRED,
    INVALID_IDEMPOTENCY_KEY,
    PAYOUT_ALREADY_OPEN,
    INVALID_TRANSITION,
    INVALID_FILTER,
    INVALID_NAME,
    EXCEEDS_TARGET,
    INSUFFICIENT_GOAL_FUNDS,
    GOAL_CLOSED;

    public String code() {
        return name().toLowerCase();
    }
}
