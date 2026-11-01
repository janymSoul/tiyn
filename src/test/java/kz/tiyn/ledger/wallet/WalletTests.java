package kz.tiyn.ledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
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
        Wallet wallet = Wallet.restore(TEEN, "KZT", 1000, 0);
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
        Wallet wallet = Wallet.restore(TEEN, "KZT", 1000, 0);

        assertError(() -> wallet.creditTaskReward(UUID.randomUUID(), UUID.randomUUID(), 0), LedgerError.INVALID_AMOUNT);
        assertError(
                () -> wallet.creditTaskReward(UUID.randomUUID(), UUID.randomUUID(), -5), LedgerError.INVALID_AMOUNT);
        assertThat(wallet.balanceAmount()).isEqualTo(1000);
    }

    @Test
    void onlyTengeIsSupported() {
        assertThat(Wallet.requireSupported("KZT")).isEqualTo("KZT");
        assertError(() -> Wallet.requireSupported("USD"), LedgerError.UNSUPPORTED_CURRENCY);
        assertError(() -> Wallet.requireSupported(null), LedgerError.UNSUPPORTED_CURRENCY);
    }

    @Test
    void availableIsTheBalanceMinusWhatIsLocked() {
        assertThat(Wallet.restore(TEEN, "KZT", 1000, 300).availableAmount()).isEqualTo(700);
    }

    @Test
    void walletsAreEqualByTeenAndCurrencyNotByBalance() {
        assertThat(Wallet.restore(TEEN, "KZT", 1, 0)).isEqualTo(Wallet.restore(TEEN, "KZT", 99, 5));
        assertThat(Wallet.restore(TEEN, "KZT", 1, 0)).isNotEqualTo(Wallet.restore(UUID.randomUUID(), "KZT", 1, 0));
    }
}
