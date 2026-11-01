package kz.tiyn.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskApproved;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@AutoConfigureMockMvc
class LedgerFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    BalanceReader balances;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    private UUID approvedTask(Family f, long reward, String key) throws Exception {
        UUID id = fx.createTask(f, reward);
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"done\"}", f.teen().bearer(), "Idempotency-Key", key)
                .andExpect(status().isOk());
        fx.send("/tasks/" + id + "/approve", "", f.parent().bearer()).andExpect(status().isOk());
        return id;
    }

    private void awaitBalance(Family f, long approved) {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(balances.balance(f.teen().id()).approvedAmount())
                        .isEqualTo(approved));
    }

    @Test
    void approvingATaskCreditsTheTeensBalance() throws Exception {
        Family f = fx.linkedFamily();

        approvedTask(f, 500_00, "l1");
        awaitBalance(f, 500_00);

        mvc.perform(get("/balance").header("Authorization", f.teen().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedAmount").value(500_00))
                .andExpect(jsonPath("$.lockedAmount").value(0))
                .andExpect(jsonPath("$.availableAmount").value(500_00))
                .andExpect(jsonPath("$.currency").value("KZT"));
    }

    @Test
    void severalApprovedTasksAddUp() throws Exception {
        Family f = fx.linkedFamily();

        approvedTask(f, 100, "m1");
        approvedTask(f, 250, "m2");

        awaitBalance(f, 350);
    }

    @Test
    void approvingTheSameTaskAgainDoesNotPayTwice() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = approvedTask(f, 300, "n1");
        awaitBalance(f, 300);

        fx.send("/tasks/" + id + "/approve", "", f.parent().bearer()).andExpect(status().isOk());

        assertThat(balances.balance(f.teen().id()).approvedAmount()).isEqualTo(300);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ledger.ledger_entries WHERE ref_id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void aRedeliveredApprovalEventIsCreditedOnce() {
        UUID teen = UUID.randomUUID();
        var event = new TaskApproved(UUID.randomUUID(), teen, 900, "KZT");

        tx.executeWithoutResult(status -> {
            publisher.publishEvent(event);
            publisher.publishEvent(event);
        });

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() ->
                        assertThat(balances.balance(teen).approvedAmount()).isEqualTo(900));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ledger.ledger_entries WHERE teen_id = ?", Integer.class, teen))
                .isEqualTo(1));
    }

    @Test
    void aTeenWhoNeverEarnedReadsZerosAndAParentIsForbidden() throws Exception {
        Family f = fx.linkedFamily();

        mvc.perform(get("/balance").header("Authorization", f.teen().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedAmount").value(0))
                .andExpect(jsonPath("$.availableAmount").value(0));
        mvc.perform(get("/balance").header("Authorization", f.parent().bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mvc.perform(get("/balance")).andExpect(status().isUnauthorized());
    }

    @Test
    void theJournalCannotBeRewritten() throws Exception {
        Family f = fx.linkedFamily();
        approvedTask(f, 100, "j1");
        awaitBalance(f, 100);

        assertThatThrownBy(() -> jdbc.update("UPDATE ledger.ledger_entries SET balance_delta = 999"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger.ledger_entries"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }
}
