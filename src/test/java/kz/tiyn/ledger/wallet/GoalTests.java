package kz.tiyn.ledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import kz.tiyn.ledger.wallet.error.LedgerError;
import kz.tiyn.ledger.wallet.error.LedgerException;
import org.junit.jupiter.api.Test;

class GoalTests {

    private static final UUID TEEN = UUID.randomUUID();

    private static void assertError(Runnable action, LedgerError expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LedgerException.class, e -> assertThat(e.error())
                .isEqualTo(expected));
    }

    private static Goal goal(long target) {
        return Goal.create(UUID.randomUUID(), TEEN, "KZT", "Headphones", target);
    }

    @Test
    void aGoalIsActiveAndItsNameIsTrimmed() {
        Goal goal = Goal.create(UUID.randomUUID(), TEEN, "KZT", "  Headphones  ", 10_000_00);

        assertThat(goal.name()).isEqualTo("Headphones");
        assertThat(goal.state()).isEqualTo(GoalState.ACTIVE);
        assertThat(goal.targetAmount()).isEqualTo(10_000_00);
    }

    @Test
    void theNameAndTargetAreValidated() {
        assertError(() -> Goal.create(UUID.randomUUID(), TEEN, "KZT", null, 100), LedgerError.INVALID_NAME);
        assertError(() -> Goal.create(UUID.randomUUID(), TEEN, "KZT", "   ", 100), LedgerError.INVALID_NAME);
        assertError(() -> Goal.create(UUID.randomUUID(), TEEN, "KZT", "x".repeat(101), 100), LedgerError.INVALID_NAME);
        assertError(() -> Goal.create(UUID.randomUUID(), TEEN, "KZT", "ok", 0), LedgerError.INVALID_AMOUNT);
        assertThat(Goal.create(UUID.randomUUID(), TEEN, "KZT", "x".repeat(100), 1))
                .isNotNull();
    }

    @Test
    void savingIsCappedAtTheTarget() {
        Goal goal = goal(1000);

        goal.requireCanSave(0, 1000);
        goal.requireCanSave(400, 600);
        assertError(() -> goal.requireCanSave(400, 601), LedgerError.EXCEEDS_TARGET);
        assertError(() -> goal.requireCanSave(0, 0), LedgerError.INVALID_AMOUNT);
    }

    @Test
    void aClosedGoalAcceptsNoMoney() {
        Goal goal = goal(1000);
        goal.close();
        goal.close();

        assertThat(goal.state()).isEqualTo(GoalState.CLOSED);
        assertError(() -> goal.requireCanSave(0, 10), LedgerError.GOAL_CLOSED);
    }
}
