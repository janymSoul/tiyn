package kz.tiyn.ledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import kz.tiyn.ledger.PenaltyApplied;
import kz.tiyn.ledger.wallet.error.LedgerError;
import kz.tiyn.ledger.wallet.error.LedgerException;
import org.junit.jupiter.api.Test;

class WalletTests {

    private static final UUID TEEN = UUID.randomUUID();

    private static void assertError(Runnable action, LedgerError expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LedgerException.class, e -> assertThat(e.error())
                .isEqualTo(expected));
    }

    @Test
    void creditingARewardRaisesTheBalanceAndRecordsTheEntry() {
        Wallet wallet = Wallet.restore(TEEN, "KZT", 1000, 0, 0);
        UUID entryId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        LedgerEntry entry = wallet.creditTaskReward(entryId, taskId, 500);

        assertThat(wallet.balanceAmount()).isEqualTo(1500);
        assertThat(entry)
                .isEqualTo(new LedgerEntry(
                        entryId, TEEN, "KZT", EntryKind.TASK_REWARD, 500, 0, taskId, "REWARD-TASK-" + taskId));
    }

    @Test
    void aRewardMustBePositive() {
        Wallet wallet = Wallet.restore(TEEN, "KZT", 1000, 0, 0);

        assertError(() -> wallet.creditTaskReward(UUID.randomUUID(), UUID.randomUUID(), 0), LedgerError.INVALID_AMOUNT);
        assertError(
                () -> wallet.creditTaskReward(UUID.randomUUID(), UUID.randomUUID(), -5), LedgerError.INVALID_AMOUNT);
        assertThat(wallet.balanceAmount()).isEqualTo(1000);
    }

    @Test
    void anOpenPayoutRequestIsNotAvailable() {
        Wallet wallet = Wallet.restore(TEEN, "KZT", 1000, 100, 300);

        assertThat(wallet.availableAmount()).isEqualTo(600);
        wallet.requireCanRequest(600);
        assertError(() -> wallet.requireCanRequest(601), LedgerError.INSUFFICIENT_FUNDS);
    }

    @Test
    void aPayoutDebitLowersTheBalanceAndRecordsTheEntry() {
        Wallet wallet = Wallet.restore(TEEN, "KZT", 1000, 0, 400);
        UUID entryId = UUID.randomUUID();
        UUID payoutId = UUID.randomUUID();

        LedgerEntry entry = wallet.debitForPayout(entryId, payoutId, 400);

        assertThat(wallet.balanceAmount()).isEqualTo(600);
        assertThat(entry)
                .isEqualTo(new LedgerEntry(
                        entryId, TEEN, "KZT", EntryKind.PAYOUT, -400, 0, payoutId, "PAYOUT-" + payoutId));
    }

    @Test
    void aPayoutCannotDebitLockedFundsOrMoreThanTheBalance() {
        Wallet wallet = Wallet.restore(TEEN, "KZT", 1000, 700, 400);

        assertError(
                () -> wallet.debitForPayout(UUID.randomUUID(), UUID.randomUUID(), 301), LedgerError.INSUFFICIENT_FUNDS);
        assertError(() -> wallet.debitForPayout(UUID.randomUUID(), UUID.randomUUID(), 0), LedgerError.INVALID_AMOUNT);
        assertThat(wallet.balanceAmount()).isEqualTo(1000);
    }

    @Test
    void lockingMovesMoneyFromAvailableIntoTheGoalWithoutChangingTheBalance() {
        Wallet wallet = Wallet.restore(TEEN, "KZT", 1500, 200, 300);
        UUID entryId = UUID.randomUUID();
        UUID goalId = UUID.randomUUID();

        LedgerEntry entry = wallet.lockForGoal(entryId, goalId, 1000, "k");

        assertThat(wallet.balanceAmount()).isEqualTo(1500);
        assertThat(wallet.lockedAmount()).isEqualTo(1200);
        assertThat(wallet.availableAmount()).isZero();
        assertThat(entry).isEqualTo(new LedgerEntry(entryId, TEEN, "KZT", EntryKind.GOAL_LOCK, 0, 1000, goalId, "k"));
    }

    @Test
    void lockingCannotUseHeldOrAlreadyLockedMoney() {
        Wallet wallet = Wallet.restore(TEEN, "KZT", 1500, 200, 300);

        assertError(
                () -> wallet.lockForGoal(UUID.randomUUID(), UUID.randomUUID(), 1001, "k"),
                LedgerError.INSUFFICIENT_FUNDS);
        assertError(() -> wallet.lockForGoal(UUID.randomUUID(), UUID.randomUUID(), 0, "k"), LedgerError.INVALID_AMOUNT);
        assertThat(wallet.lockedAmount()).isEqualTo(200);
    }

    @Test
    void unlockingReleasesAtMostWhatTheGoalHolds() {
        Wallet wallet = Wallet.restore(TEEN, "KZT", 1500, 700, 0);
        UUID entryId = UUID.randomUUID();
        UUID goalId = UUID.randomUUID();

        LedgerEntry entry = wallet.unlockFromGoal(entryId, goalId, 400, 500, "k");

        assertThat(wallet.lockedAmount()).isEqualTo(300);
        assertThat(wallet.balanceAmount()).isEqualTo(1500);
        assertThat(entry).isEqualTo(new LedgerEntry(entryId, TEEN, "KZT", EntryKind.GOAL_UNLOCK, 0, -400, goalId, "k"));
        assertError(
                () -> wallet.unlockFromGoal(UUID.randomUUID(), goalId, 101, 100, "k"),
                LedgerError.INSUFFICIENT_GOAL_FUNDS);
    }

    @Test
    void aPenaltyLowersTheBalanceEvenBelowZeroAndRecordsItsEvent() {
        Wallet wallet = Wallet.restore(TEEN, "KZT", 300, 100, 0);
        UUID entryId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        LedgerEntry entry = wallet.applyPenalty(entryId, taskId, 500);

        assertThat(wallet.balanceAmount()).isEqualTo(-200);
        assertThat(wallet.availableAmount()).isEqualTo(-300);
        assertThat(entry)
                .isEqualTo(new LedgerEntry(
                        entryId, TEEN, "KZT", EntryKind.PENALTY, -500, 0, taskId, "PENALTY-TASK-" + taskId));
        assertThat(wallet.pullEvents()).containsExactly(new PenaltyApplied(entryId, TEEN, taskId, 500, "KZT"));
        assertThat(wallet.pullEvents()).isEmpty();
        assertError(() -> wallet.applyPenalty(UUID.randomUUID(), taskId, 0), LedgerError.INVALID_AMOUNT);
    }

    @Test
    void nothingCanBeRequestedOrLockedWhileAvailableIsNegative() {
        Wallet wallet = Wallet.restore(TEEN, "KZT", -200, 0, 0);

        assertError(() -> wallet.requireCanRequest(1), LedgerError.INSUFFICIENT_FUNDS);
        assertError(
                () -> wallet.lockForGoal(UUID.randomUUID(), UUID.randomUUID(), 1, "k"), LedgerError.INSUFFICIENT_FUNDS);
    }

    @Test
    void onlyTengeIsSupported() {
        assertThat(Wallet.requireSupported("KZT")).isEqualTo("KZT");
        assertError(() -> Wallet.requireSupported("USD"), LedgerError.UNSUPPORTED_CURRENCY);
        assertError(() -> Wallet.requireSupported(null), LedgerError.UNSUPPORTED_CURRENCY);
    }

    @Test
    void availableIsTheBalanceMinusWhatIsLocked() {
        assertThat(Wallet.restore(TEEN, "KZT", 1000, 300, 0).availableAmount()).isEqualTo(700);
    }

    @Test
    void walletsAreEqualByTeenAndCurrencyNotByBalance() {
        assertThat(Wallet.restore(TEEN, "KZT", 1, 0, 0)).isEqualTo(Wallet.restore(TEEN, "KZT", 99, 5, 0));
        assertThat(Wallet.restore(TEEN, "KZT", 1, 0, 0))
                .isNotEqualTo(Wallet.restore(UUID.randomUUID(), "KZT", 1, 0, 0));
    }
}
