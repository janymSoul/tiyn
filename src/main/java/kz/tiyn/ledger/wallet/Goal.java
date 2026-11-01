package kz.tiyn.ledger.wallet;

import java.util.Objects;
import java.util.UUID;
import kz.tiyn.ledger.wallet.error.LedgerError;
import kz.tiyn.ledger.wallet.error.LedgerException;

final class Goal {

    static final int MAX_NAME_LENGTH = 100;

    private final UUID id;
    private final UUID teenId;
    private final String currency;
    private final String name;
    private final long targetAmount;
    private GoalState state;

    private Goal(UUID id, UUID teenId, String currency, String name, long targetAmount, GoalState state) {
        this.id = id;
        this.teenId = teenId;
        this.currency = currency;
        this.name = name;
        this.targetAmount = targetAmount;
        this.state = state;
    }

    static Goal create(UUID id, UUID teenId, String currency, String name, long targetAmount) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new LedgerException(LedgerError.INVALID_NAME);
        }
        if (targetAmount <= 0) {
            throw new LedgerException(LedgerError.INVALID_AMOUNT);
        }
        return new Goal(id, teenId, currency, trimmed, targetAmount, GoalState.ACTIVE);
    }

    static Goal restore(UUID id, UUID teenId, String currency, String name, long targetAmount, GoalState state) {
        return new Goal(id, teenId, currency, name, targetAmount, state);
    }

    void requireCanSave(long saved, long amount) {
        if (amount <= 0) {
            throw new LedgerException(LedgerError.INVALID_AMOUNT);
        }
        if (state == GoalState.CLOSED) {
            throw new LedgerException(LedgerError.GOAL_CLOSED);
        }
        if (saved + amount > targetAmount) {
            throw new LedgerException(LedgerError.EXCEEDS_TARGET);
        }
    }

    void close() {
        state = GoalState.CLOSED;
    }

    UUID id() {
        return id;
    }

    UUID teenId() {
        return teenId;
    }

    String currency() {
        return currency;
    }

    String name() {
        return name;
    }

    long targetAmount() {
        return targetAmount;
    }

    GoalState state() {
        return state;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Goal goal && id.equals(goal.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
