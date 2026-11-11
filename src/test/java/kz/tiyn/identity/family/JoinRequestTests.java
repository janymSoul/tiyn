package kz.tiyn.identity.family;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import kz.tiyn.identity.JoinRequested;
import kz.tiyn.identity.account.User;
import kz.tiyn.identity.account.error.IdentityError;
import kz.tiyn.identity.account.error.IdentityException;
import kz.tiyn.platform.security.Role;
import org.junit.jupiter.api.Test;

class JoinRequestTests {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    private final User parent = user(Role.PARENT);
    private final User teen = user(Role.TEEN);

    @Test
    void aSubmittedRequestIsPendingAndRecordsOneEvent() {
        var request = JoinRequest.submit(UUID.randomUUID(), parent, teen);

        assertThat(request.state()).isEqualTo(JoinRequestState.PENDING);
        assertThat(request.isPending()).isTrue();
        assertThat(request.decidedAt()).isNull();
        assertThat(request.pullEvents()).containsExactly(new JoinRequested(request.id(), parent.id(), teen.id()));
        assertThat(request.pullEvents()).isEmpty();
    }

    @Test
    void onlyATeenCanAskAParent() {
        for (var pair : List.of(
                List.of(Role.TEEN, Role.TEEN), List.of(Role.PARENT, Role.PARENT), List.of(Role.TEEN, Role.PARENT))) {
            assertThatThrownBy(() -> JoinRequest.submit(UUID.randomUUID(), user(pair.get(0)), user(pair.get(1))))
                    .isInstanceOfSatisfying(
                            IdentityException.class, e -> assertThat(e.error()).isEqualTo(IdentityError.FORBIDDEN));
        }
    }

    @Test
    void theParentApprovesAPendingRequest() {
        var request = pending();

        request.approveBy(parent.id(), NOW);

        assertThat(request.state()).isEqualTo(JoinRequestState.APPROVED);
        assertThat(request.isPending()).isFalse();
        assertThat(request.decidedAt()).isEqualTo(NOW);
    }

    @Test
    void theParentDeclinesAPendingRequest() {
        var request = pending();

        request.declineBy(parent.id(), NOW);

        assertThat(request.state()).isEqualTo(JoinRequestState.DECLINED);
        assertThat(request.decidedAt()).isEqualTo(NOW);
    }

    @Test
    void theTeenWithdrawsAPendingRequest() {
        var request = pending();

        request.withdrawBy(teen.id(), NOW);

        assertThat(request.state()).isEqualTo(JoinRequestState.WITHDRAWN);
        assertThat(request.decidedAt()).isEqualTo(NOW);
    }

    @Test
    void repeatingTheDecisionThatWasTakenChangesNothing() {
        var approved = pending();
        approved.approveBy(parent.id(), NOW);
        approved.approveBy(parent.id(), NOW.plusSeconds(60));
        var declined = pending();
        declined.declineBy(parent.id(), NOW);
        declined.declineBy(parent.id(), NOW.plusSeconds(60));
        var withdrawn = pending();
        withdrawn.withdrawBy(teen.id(), NOW);
        withdrawn.withdrawBy(teen.id(), NOW.plusSeconds(60));

        assertThat(approved.state()).isEqualTo(JoinRequestState.APPROVED);
        assertThat(approved.decidedAt()).isEqualTo(NOW);
        assertThat(declined.state()).isEqualTo(JoinRequestState.DECLINED);
        assertThat(declined.decidedAt()).isEqualTo(NOW);
        assertThat(withdrawn.state()).isEqualTo(JoinRequestState.WITHDRAWN);
        assertThat(withdrawn.decidedAt()).isEqualTo(NOW);
    }

    @Test
    void aDecidedRequestCannotTakeAnyOtherDecision() {
        for (JoinRequestState decided :
                List.of(JoinRequestState.APPROVED, JoinRequestState.DECLINED, JoinRequestState.WITHDRAWN)) {
            for (var attempt : attemptsOtherThan(decided)) {
                var request = JoinRequest.restore(UUID.randomUUID(), parent.id(), teen.id(), decided, NOW);

                assertThatThrownBy(() -> attempt.accept(request, NOW.plusSeconds(60)))
                        .as(decided + " then another decision")
                        .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.error())
                                .isEqualTo(IdentityError.INVALID_TRANSITION));
                assertThat(request.state()).isEqualTo(decided);
                assertThat(request.decidedAt()).isEqualTo(NOW);
            }
        }
    }

    @Test
    void onlyTheRequestedParentDecidesAndOnlyTheRequestingTeenWithdraws() {
        var request = pending();
        UUID stranger = UUID.randomUUID();

        for (Runnable attempt : List.<Runnable>of(
                () -> request.approveBy(stranger, NOW),
                () -> request.approveBy(teen.id(), NOW),
                () -> request.declineBy(stranger, NOW),
                () -> request.declineBy(teen.id(), NOW),
                () -> request.withdrawBy(stranger, NOW),
                () -> request.withdrawBy(parent.id(), NOW))) {
            assertThatThrownBy(attempt::run).isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.error())
                    .isEqualTo(IdentityError.FORBIDDEN));
        }
        assertThat(request.isPending()).isTrue();
    }

    @Test
    void aStrangerIsRefusedBeforeTheStateIsConsidered() {
        var request = JoinRequest.restore(UUID.randomUUID(), parent.id(), teen.id(), JoinRequestState.DECLINED, NOW);

        assertThatThrownBy(() -> request.approveBy(UUID.randomUUID(), NOW))
                .isInstanceOfSatisfying(
                        IdentityException.class, e -> assertThat(e.error()).isEqualTo(IdentityError.FORBIDDEN));
    }

    private List<BiConsumer<JoinRequest, Instant>> attemptsOtherThan(JoinRequestState decided) {
        List<BiConsumer<JoinRequest, Instant>> attempts = new ArrayList<>();
        if (decided != JoinRequestState.APPROVED) {
            attempts.add((request, now) -> request.approveBy(parent.id(), now));
        }
        if (decided != JoinRequestState.DECLINED) {
            attempts.add((request, now) -> request.declineBy(parent.id(), now));
        }
        if (decided != JoinRequestState.WITHDRAWN) {
            attempts.add((request, now) -> request.withdrawBy(teen.id(), now));
        }
        return attempts;
    }

    private JoinRequest pending() {
        return JoinRequest.submit(UUID.randomUUID(), parent, teen);
    }

    private static User user(Role role) {
        return User.restore(UUID.randomUUID(), "sub-" + UUID.randomUUID(), role, null, "Name", false);
    }
}
