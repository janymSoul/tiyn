package kz.tiyn.ledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import kz.tiyn.ledger.Balance;
import kz.tiyn.ledger.BalanceReader;
import kz.tiyn.ledger.PenaltyApplied;
import kz.tiyn.ledger.wallet.error.LedgerError;
import kz.tiyn.ledger.wallet.error.LedgerException;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskExpired;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class PenaltyFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    BalanceReader balances;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PenaltyService penalties;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ApplicationEvents events;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    private void earn(Family f, long reward) throws Exception {
        long before = balances.balance(f.teen().id()).balanceAmount();
        UUID id = fx.createTask(f, reward);
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"done\"}", f.teen().bearer(), "Idempotency-Key", "s-" + id)
                .andExpect(status().isOk());
        fx.send("/tasks/" + id + "/approve", "", f.parent().bearer()).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(balances.balance(f.teen().id()).balanceAmount())
                        .isEqualTo(before + reward));
    }

    private Family familyWith(long reward) throws Exception {
        Family f = fx.linkedFamily();
        earn(f, reward);
        return f;
    }

    private void miss(Family f, long penalty) {
        penalties.applyMissedTaskPenalty(UUID.randomUUID(), f.teen().id(), penalty, "KZT");
    }

    private int penaltyEntries(UUID teenId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger.ledger_entries WHERE teen_id = ? AND kind = 'PENALTY'",
                Integer.class,
                teenId);
    }

    @Test
    void anExpiredTaskEventDebitsThePenaltyOnceEvenWhenDeliveredTwice() throws Exception {
        Family f = familyWith(500_00);
        TaskExpired event = new TaskExpired(UUID.randomUUID(), f.teen().id(), 200_00, "KZT");

        tx.executeWithoutResult(status -> {
            publisher.publishEvent(event);
            publisher.publishEvent(event);
        });

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(balances.balance(f.teen().id()).balanceAmount())
                        .isEqualTo(300_00));
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(penaltyEntries(f.teen().id())).isEqualTo(1));
        assertThat(jdbc.queryForObject(
                        "SELECT ref_id FROM ledger.ledger_entries WHERE teen_id = ? AND kind = 'PENALTY'",
                        UUID.class,
                        f.teen().id()))
                .isEqualTo(event.taskId());
    }

    @Test
    void repeatingThePenaltyForATaskChangesNothingAndPublishesOneEvent() throws Exception {
        Family f = familyWith(500_00);
        UUID task = UUID.randomUUID();

        penalties.applyMissedTaskPenalty(task, f.teen().id(), 100_00, "KZT");
        penalties.applyMissedTaskPenalty(task, f.teen().id(), 100_00, "KZT");

        assertThat(balances.balance(f.teen().id()).balanceAmount()).isEqualTo(400_00);
        assertThat(penaltyEntries(f.teen().id())).isEqualTo(1);
        assertThat(events.stream(PenaltyApplied.class).filter(e -> e.taskId().equals(task)))
                .hasSize(1);
    }

    @Test
    void noPenaltyMeansNothingIsWritten() throws Exception {
        Family f = familyWith(500_00);

        penalties.applyMissedTaskPenalty(UUID.randomUUID(), f.teen().id(), 0, "KZT");

        assertThat(penaltyEntries(f.teen().id())).isZero();
        assertThat(balances.balance(f.teen().id()).balanceAmount()).isEqualTo(500_00);
        assertThatThrownBy(() -> penalties.applyMissedTaskPenalty(
                        UUID.randomUUID(), f.teen().id(), 10, "USD"))
                .isInstanceOfSatisfying(
                        LedgerException.class, e -> assertThat(e.error()).isEqualTo(LedgerError.UNSUPPORTED_CURRENCY));
    }

    @Test
    void aPenaltyBeyondTheBalanceLeavesADebtThatRewardsPayDown() throws Exception {
        Family f = familyWith(500_00);

        miss(f, 800_00);

        Balance debt = balances.balance(f.teen().id());
        assertThat(debt.balanceAmount()).isEqualTo(-300_00);
        assertThat(debt.availableAmount()).isEqualTo(-300_00);

        earn(f, 1000_00);
        assertThat(balances.balance(f.teen().id()).balanceAmount()).isEqualTo(700_00);
    }

    @Test
    void whileInDebtNothingCanBeRequestedOrLockedButGoalsCanBeReleased() throws Exception {
        Family f = familyWith(500_00);
        String goal = fx.send(
                        "/goals",
                        "{\"name\":\"Bike\",\"targetAmount\":300000}",
                        f.teen().bearer())
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID goalId = UUID.fromString(json.readTree(goal).get("id").asText());
        fx.send("/goals/" + goalId + "/lock", "{\"amount\":10000}", f.teen().bearer(), "Idempotency-Key", "l1")
                .andExpect(status().isOk());

        miss(f, 600_00);

        assertThat(balances.balance(f.teen().id()).availableAmount()).isEqualTo(-200_00);
        String payout = "{\"parentId\":\"" + f.parent().id() + "\",\"amount\":10000}";
        fx.send("/payouts", payout, f.teen().bearer(), "Idempotency-Key", "p1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("insufficient_funds"));
        fx.send("/goals/" + goalId + "/lock", "{\"amount\":1}", f.teen().bearer(), "Idempotency-Key", "l2")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("insufficient_funds"));
        fx.send("/goals/" + goalId + "/unlock", "{\"amount\":10000}", f.teen().bearer(), "Idempotency-Key", "u1")
                .andExpect(status().isOk());
        assertThat(balances.balance(f.teen().id()).lockedAmount()).isZero();
    }

    @Test
    void aPenaltyThatRemovesTheCoverOfAnOpenPayoutBlocksFulfilmentButNotDeclining() throws Exception {
        Family f = familyWith(500_00);
        String payout = "{\"parentId\":\"" + f.parent().id() + "\",\"amount\":30000}";
        String response = fx.send("/payouts", payout, f.teen().bearer(), "Idempotency-Key", "p1")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID id = UUID.fromString(json.readTree(response).get("id").asText());

        miss(f, 400_00);

        fx.send("/payouts/" + id + "/fulfil", "", f.parent().bearer())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("insufficient_funds"));
        fx.send("/payouts/" + id + "/decline", "", f.parent().bearer()).andExpect(status().isOk());
        assertThat(balances.balance(f.teen().id()).balanceAmount()).isEqualTo(100_00);
    }
}
