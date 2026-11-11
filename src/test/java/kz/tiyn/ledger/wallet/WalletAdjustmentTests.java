package kz.tiyn.ledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import kz.tiyn.ledger.AdjustmentKind;
import kz.tiyn.ledger.BalanceAdjusted;
import kz.tiyn.ledger.wallet.dto.AdjustmentRequest;
import kz.tiyn.ledger.wallet.error.LedgerError;
import kz.tiyn.ledger.wallet.error.LedgerException;
import org.junit.jupiter.api.Test;

class WalletAdjustmentTests {

    private static final UUID TEEN = UUID.randomUUID();
    private static final UUID PARENT = UUID.randomUUID();
    private static final long CAP = 1_000_00;

    private static Adjustment adjustment(String kind, Long amount, String reason) {
        return Adjustment.of(new AdjustmentRequest(kind, amount, reason), CAP);
    }

    private static void assertError(Runnable action, LedgerError expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LedgerException.class, e -> assertThat(e.error())
                .isEqualTo(expected));
    }

    @Test
    void aBonusRaisesTheBalanceAndRecordsTheActorAndTheReason() {
        Wallet wallet = Wallet.restore(TEEN, "KZT", 1000, 0, 0);
        UUID entryId = UUID.randomUUID();

        LedgerEntry entry = wallet.adjust(entryId, PARENT, adjustment("BONUS", 500L, "Great grades"));

        assertThat(wallet.balanceAmount()).isEqualTo(1500);
        assertThat(entry)
                .isEqualTo(new LedgerEntry(
                        entryId,
                        TEEN,
                        "KZT",
                        EntryKind.MANUAL_BONUS,
                        500,
                        0,
                        null,
                        "ADJUSTMENT-" + entryId,
                        "Great grades",
                        PARENT));
        assertThat(wallet.pullEvents())
                .containsExactly(new BalanceAdjusted(entryId, TEEN, PARENT, AdjustmentKind.BONUS, 500, "KZT"));
    }

    @Test
    void aPenaltyLowersTheBalanceAndRecordsTheActorAndTheReason() {
        Wallet wallet = Wallet.restore(TEEN, "KZT", 1000, 0, 0);
        UUID entryId = UUID.randomUUID();

        LedgerEntry entry = wallet.adjust(entryId, PARENT, adjustment("PENALTY", 400L, "Broke the window"));

        assertThat(wallet.balanceAmount()).isEqualTo(600);
        assertThat(entry.kind()).isEqualTo(EntryKind.MANUAL_PENALTY);
        assertThat(entry.balanceDelta()).isEqualTo(-400);
        assertThat(entry.lockedDelta()).isZero();
        assertThat(entry.note()).isEqualTo("Broke the window");
        assertThat(entry.actorId()).isEqualTo(PARENT);
        assertThat(wallet.pullEvents())
                .containsExactly(new BalanceAdjusted(entryId, TEEN, PARENT, AdjustmentKind.PENALTY, 400, "KZT"));
    }

    @Test
    void aPenaltyBeyondTheBalanceLeavesADebtAndKeepsLockedFunds() {
        Wallet wallet = Wallet.restore(TEEN, "KZT", 300, 100, 50);

        wallet.adjust(UUID.randomUUID(), PARENT, adjustment("PENALTY", 800L, "Lost the phone"));

        assertThat(wallet.balanceAmount()).isEqualTo(-500);
        assertThat(wallet.lockedAmount()).isEqualTo(100);
        assertThat(wallet.availableAmount()).isEqualTo(-650);
    }

    @Test
    void aBonusPaysDownADebt() {
        Wallet wallet = Wallet.restore(TEEN, "KZT", -300, 0, 0);

        wallet.adjust(UUID.randomUUID(), PARENT, adjustment("BONUS", 1000L, "Forgiven"));

        assertThat(wallet.balanceAmount()).isEqualTo(700);
    }

    @Test
    void theAmountMustBePositiveAndWithinTheCap() {
        assertError(() -> adjustment("BONUS", 0L, "ok"), LedgerError.INVALID_AMOUNT);
        assertError(() -> adjustment("BONUS", -5L, "ok"), LedgerError.INVALID_AMOUNT);
        assertError(() -> adjustment("PENALTY", null, "ok"), LedgerError.INVALID_AMOUNT);
        assertError(() -> adjustment("PENALTY", CAP + 1, "ok"), LedgerError.INVALID_AMOUNT);
        assertThat(adjustment("PENALTY", CAP, "ok").amount()).isEqualTo(CAP);
    }

    @Test
    void theKindMustBeBonusOrPenalty() {
        assertError(() -> adjustment(null, 10L, "ok"), LedgerError.INVALID_KIND);
        assertError(() -> adjustment("", 10L, "ok"), LedgerError.INVALID_KIND);
        assertError(() -> adjustment("bonus", 10L, "ok"), LedgerError.INVALID_KIND);
        assertError(() -> adjustment("TASK_REWARD", 10L, "ok"), LedgerError.INVALID_KIND);
    }

    @Test
    void theReasonIsRequiredAndAtMost140Characters() {
        assertError(() -> adjustment("BONUS", 10L, null), LedgerError.INVALID_REASON);
        assertError(() -> adjustment("BONUS", 10L, ""), LedgerError.INVALID_REASON);
        assertError(() -> adjustment("BONUS", 10L, "   \n\t "), LedgerError.INVALID_REASON);
        assertError(() -> adjustment("BONUS", 10L, "​\u0007"), LedgerError.INVALID_REASON);
        assertError(() -> adjustment("BONUS", 10L, "x".repeat(141)), LedgerError.INVALID_REASON);
        assertThat(adjustment("BONUS", 10L, "x".repeat(140)).reason()).hasSize(140);
    }

    @Test
    void theReasonIsTrimmedAndStrippedOfControlAndFormatCharacters() {
        assertThat(adjustment("BONUS", 10L, "  Helped​ with\nthe\tgarden\u0007  ")
                        .reason())
                .isEqualTo("Helped with the garden");
        assertThat(adjustment("BONUS", 10L, "‮evil‬").reason()).isEqualTo("evil");
    }

    @Test
    void theReasonLengthCountsCharactersNotUtf16Units() {
        String emoji = "😀".repeat(140);

        assertThat(adjustment("BONUS", 10L, emoji).reason()).isEqualTo(emoji);
        assertError(() -> adjustment("BONUS", 10L, emoji + "a"), LedgerError.INVALID_REASON);
    }

    @Test
    void kazakhAndRussianTextSurvivesNormalisation() {
        assertThat(adjustment("PENALTY", 10L, " Үйді жинамады / не убрал ").reason())
                .isEqualTo("Үйді жинамады / не убрал");
    }
}
