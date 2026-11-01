package kz.tiyn.ledger.wallet;

import java.util.Objects;
import java.util.UUID;
import kz.tiyn.ledger.wallet.error.LedgerError;
import kz.tiyn.ledger.wallet.error.LedgerException;

final class Wallet {

    static final String KZT = "KZT";

    private final UUID teenId;
    private final String currency;
    private long balanceAmount;
    private final long lockedAmount;
    private final long openRequestedAmount;

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
