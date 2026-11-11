package kz.tiyn.ledger.wallet;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kz.tiyn.ledger.BalanceAdjusted;
import kz.tiyn.ledger.PenaltyApplied;
import kz.tiyn.ledger.wallet.error.LedgerError;
import kz.tiyn.ledger.wallet.error.LedgerException;
import kz.tiyn.platform.event.EventLog;

final class Wallet {

    static final String KZT = "KZT";

    private final UUID teenId;
    private final String currency;
    private long balanceAmount;
    private long lockedAmount;
    private final long openRequestedAmount;
    private final EventLog events = new EventLog();

    private Wallet(UUID teenId, String currency, long balanceAmount, long lockedAmount, long openRequestedAmount) {
        this.teenId = teenId;
        this.currency = currency;
        this.balanceAmount = balanceAmount;
        this.lockedAmount = lockedAmount;
        this.openRequestedAmount = openRequestedAmount;
    }

    static Wallet restore(
            UUID teenId, String currency, long balanceAmount, long lockedAmount, long openRequestedAmount) {
        return new Wallet(teenId, currency, balanceAmount, lockedAmount, openRequestedAmount);
    }

    static String requireSupported(String currency) {
        if (!KZT.equals(currency)) {
            throw new LedgerException(LedgerError.UNSUPPORTED_CURRENCY);
        }
        return currency;
    }

    LedgerEntry creditTaskReward(UUID entryId, UUID taskId, long amount) {
        if (amount <= 0) {
            throw new LedgerException(LedgerError.INVALID_AMOUNT);
        }
        balanceAmount += amount;
        return new LedgerEntry(
                entryId, teenId, currency, EntryKind.TASK_REWARD, amount, 0, taskId, LedgerEntry.rewardKey(taskId));
    }

    LedgerEntry debitForPayout(UUID entryId, UUID payoutId, long amount) {
        if (amount <= 0) {
            throw new LedgerException(LedgerError.INVALID_AMOUNT);
        }
        if (amount > balanceAmount - lockedAmount) {
            throw new LedgerException(LedgerError.INSUFFICIENT_FUNDS);
        }
        balanceAmount -= amount;
        return new LedgerEntry(
                entryId, teenId, currency, EntryKind.PAYOUT, -amount, 0, payoutId, LedgerEntry.payoutKey(payoutId));
    }

    LedgerEntry applyPenalty(UUID entryId, UUID taskId, long amount) {
        if (amount <= 0) {
            throw new LedgerException(LedgerError.INVALID_AMOUNT);
        }
        balanceAmount -= amount;
        events.record(new PenaltyApplied(entryId, teenId, taskId, amount, currency));
        return new LedgerEntry(
                entryId, teenId, currency, EntryKind.PENALTY, -amount, 0, taskId, LedgerEntry.penaltyKey(taskId));
    }

    LedgerEntry adjust(UUID entryId, UUID parentId, Adjustment adjustment, String idempotencyKey) {
        balanceAmount += adjustment.balanceDelta();
        events.record(new BalanceAdjusted(entryId, teenId, parentId, adjustment.kind(), adjustment.amount(), currency));
        return new LedgerEntry(
                entryId,
                teenId,
                currency,
                adjustment.entryKind(),
                adjustment.balanceDelta(),
                0,
                null,
                idempotencyKey,
                adjustment.reason(),
                parentId);
    }

    List<Object> pullEvents() {
        return events.drain();
    }

    LedgerEntry lockForGoal(UUID entryId, UUID goalId, long amount, String idempotencyKey) {
        if (amount <= 0) {
            throw new LedgerException(LedgerError.INVALID_AMOUNT);
        }
        if (amount > availableAmount()) {
            throw new LedgerException(LedgerError.INSUFFICIENT_FUNDS);
        }
        lockedAmount += amount;
        return new LedgerEntry(entryId, teenId, currency, EntryKind.GOAL_LOCK, 0, amount, goalId, idempotencyKey);
    }

    LedgerEntry unlockFromGoal(UUID entryId, UUID goalId, long amount, long saved, String idempotencyKey) {
        if (amount <= 0) {
            throw new LedgerException(LedgerError.INVALID_AMOUNT);
        }
        if (amount > saved) {
            throw new LedgerException(LedgerError.INSUFFICIENT_GOAL_FUNDS);
        }
        lockedAmount -= amount;
        return new LedgerEntry(entryId, teenId, currency, EntryKind.GOAL_UNLOCK, 0, -amount, goalId, idempotencyKey);
    }

    void requireCanRequest(long amount) {
        if (amount > availableAmount()) {
            throw new LedgerException(LedgerError.INSUFFICIENT_FUNDS);
        }
    }

    long availableAmount() {
        return balanceAmount - lockedAmount - openRequestedAmount;
    }

    UUID teenId() {
        return teenId;
    }

    String currency() {
        return currency;
    }

    long balanceAmount() {
        return balanceAmount;
    }

    long lockedAmount() {
        return lockedAmount;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Wallet wallet && teenId.equals(wallet.teenId) && currency.equals(wallet.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(teenId, currency);
    }
}
