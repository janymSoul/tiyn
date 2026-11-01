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

    private Wallet(UUID teenId, String currency, long balanceAmount, long lockedAmount) {
        this.teenId = teenId;
        this.currency = currency;
        this.balanceAmount = balanceAmount;
        this.lockedAmount = lockedAmount;
    }

    static Wallet restore(UUID teenId, String currency, long balanceAmount, long lockedAmount) {
        return new Wallet(teenId, currency, balanceAmount, lockedAmount);
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

    long availableAmount() {
        return balanceAmount - lockedAmount;
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
