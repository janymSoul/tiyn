package kz.tiyn.ledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import kz.tiyn.ledger.PayoutCancelled;
import kz.tiyn.ledger.PayoutFulfilled;
import kz.tiyn.ledger.PayoutRequested;
import kz.tiyn.ledger.wallet.error.LedgerError;
import kz.tiyn.ledger.wallet.error.LedgerException;
import org.junit.jupiter.api.Test;

class PayoutTests {

    private static final UUID TEEN = UUID.randomUUID();
    private static final UUID PARENT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private static void assertError(Runnable action, LedgerError expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LedgerException.class, e -> assertThat(e.error())
                .isEqualTo(expected));
    }

    private static Payout requested(long amount) {
        return Payout.request(UUID.randomUUID(), TEEN, PARENT, amount, "KZT", "key", 10_000, NOW);
    }

    @Test
    void aRequestIsOpenAndRecordsItsEvent() {
        Payout payout = requested(50_000);

        assertThat(payout.state()).isEqualTo(PayoutState.REQUESTED);
        assertThat(payout.resolvedAt()).isNull();
        assertThat(payout.pullEvents()).containsExactly(new PayoutRequested(payout.id(), TEEN, PARENT, 50_000, "KZT"));
        assertThat(payout.pullEvents()).isEmpty();
    }

    @Test
    void theAmountMustBePositiveAndReachTheMinimum() {
        assertError(() -> requested(0), LedgerError.INVALID_AMOUNT);
        assertError(() -> requested(-1), LedgerError.INVALID_AMOUNT);
        assertError(() -> requested(9_999), LedgerError.AMOUNT_TOO_SMALL);
        assertThat(requested(10_000).amount()).isEqualTo(10_000);
    }

    @Test
    void theAddressedParentFulfilsAndRepeatingIsANoOp() {
        Payout payout = requested(20_000);
        payout.pullEvents();
        Instant later = NOW.plusSeconds(60);

        payout.fulfil(PARENT, later);
        payout.fulfil(PARENT, later.plusSeconds(60));

        assertThat(payout.state()).isEqualTo(PayoutState.FULFILLED);
        assertThat(payout.resolvedAt()).isEqualTo(later);
        assertThat(payout.resolvedBy()).isEqualTo(PARENT);
        assertThat(payout.pullEvents()).containsExactly(new PayoutFulfilled(payout.id(), TEEN, PARENT, 20_000, "KZT"));
    }

    @Test
    void onlyTheAddressedParentFulfilsAndNeverAfterACancellation() {
        Payout payout = requested(20_000);

        assertError(() -> payout.fulfil(UUID.randomUUID(), NOW), LedgerError.FORBIDDEN);
        assertError(() -> payout.fulfil(TEEN, NOW), LedgerError.FORBIDDEN);
        payout.cancelByTeen(TEEN, NOW);
        assertError(() -> payout.fulfil(PARENT, NOW), LedgerError.INVALID_TRANSITION);
    }

    @Test
    void aFulfilledPayoutCannotBeCancelled() {
        Payout payout = requested(20_000);
        payout.fulfil(PARENT, NOW);

        assertError(() -> payout.cancelByTeen(TEEN, NOW), LedgerError.INVALID_TRANSITION);
        assertError(() -> payout.decline(PARENT, NOW), LedgerError.INVALID_TRANSITION);
    }

    @Test
    void theTeenCanCancelTheirOwnRequest() {
        Payout payout = requested(20_000);
        payout.pullEvents();
        Instant later = NOW.plusSeconds(60);

        payout.cancelByTeen(TEEN, later);

        assertThat(payout.state()).isEqualTo(PayoutState.CANCELLED);
        assertThat(payout.cancelReason()).isEqualTo(CancelReason.TEEN_CANCELLED);
        assertThat(payout.resolvedAt()).isEqualTo(later);
        assertThat(payout.resolvedBy()).isEqualTo(TEEN);
        assertThat(payout.pullEvents())
                .containsExactly(new PayoutCancelled(payout.id(), TEEN, PARENT, "TEEN_CANCELLED"));
    }

    @Test
    void theAddressedParentCanDecline() {
        Payout payout = requested(20_000);
        payout.pullEvents();

        payout.decline(PARENT, NOW.plusSeconds(5));

        assertThat(payout.cancelReason()).isEqualTo(CancelReason.DECLINED);
        assertThat(payout.resolvedBy()).isEqualTo(PARENT);
        assertThat(payout.pullEvents()).containsExactly(new PayoutCancelled(payout.id(), TEEN, PARENT, "DECLINED"));
    }

    @Test
    void onlyThePartiesToThePayoutMayResolveIt() {
        Payout payout = requested(20_000);

        assertError(() -> payout.cancelByTeen(UUID.randomUUID(), NOW), LedgerError.FORBIDDEN);
        assertError(() -> payout.decline(UUID.randomUUID(), NOW), LedgerError.FORBIDDEN);
        assertError(() -> payout.decline(TEEN, NOW), LedgerError.FORBIDDEN);
        assertThat(payout.state()).isEqualTo(PayoutState.REQUESTED);
    }

    @Test
    void repeatingTheSameCancellationChangesNothing() {
        Payout payout = requested(20_000);
        payout.cancelByTeen(TEEN, NOW);
        payout.pullEvents();

        payout.cancelByTeen(TEEN, NOW.plusSeconds(30));

        assertThat(payout.resolvedAt()).isEqualTo(NOW);
        assertThat(payout.pullEvents()).isEmpty();
    }

    @Test
    void aCancelledPayoutCannotBeResolvedAnotherWay() {
        Payout payout = requested(20_000);
        payout.cancelByTeen(TEEN, NOW);

        assertError(() -> payout.decline(PARENT, NOW), LedgerError.INVALID_TRANSITION);
        assertThat(payout.cancelReason()).isEqualTo(CancelReason.TEEN_CANCELLED);
    }
}
