package kz.tiyn.task.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import kz.tiyn.ledger.BalanceReader;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@AutoConfigureMockMvc
class TaskPenaltyFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    TaskExpiryTicker ticker;

    @Autowired
    BalanceReader balances;

    @Autowired
    JdbcTemplate jdbc;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    private ResultActions create(Family f, long reward, Object penalty) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("teenId", f.teen().id());
        body.put("title", "Wash dishes");
        body.put("rewardAmount", reward);
        body.put("penaltyAmount", penalty);
        body.put("currency", "KZT");
        body.put("deadlineAt", Instant.now().plusSeconds(3600).toString());
        return fx.send("/tasks", json.writeValueAsString(body), f.parent().bearer());
    }

    private UUID created(Family f, long reward, long penalty) throws Exception {
        String response = create(f, reward, penalty)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    private void afterDeadline() {
        ticker.tick(Instant.now().plusSeconds(7200));
    }

    private int penaltyEntries(Family f) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger.ledger_entries WHERE teen_id = ? AND kind = 'PENALTY'",
                Integer.class,
                f.teen().id());
    }

    private void assertNoPenaltyFor(Family f) {
        await().during(Duration.ofMillis(700))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(penaltyEntries(f)).isZero());
    }

    @Test
    void aMissedTaskCostsTheTeenItsPenalty() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = created(f, 100_00, 150_00);
        mvc.perform(get("/tasks/" + id).header("Authorization", f.teen().bearer()))
                .andExpect(jsonPath("$.penaltyAmount").value(150_00));

        afterDeadline();
        afterDeadline();

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(balances.balance(f.teen().id()).balanceAmount())
                        .isEqualTo(-150_00));
        assertThat(penaltyEntries(f)).isEqualTo(1);
    }

    @Test
    void aTaskDoneInTimeCostsNothing() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = created(f, 100_00, 150_00);
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"done\"}", f.teen().bearer(), "Idempotency-Key", "s1")
                .andExpect(status().isOk());
        fx.send("/tasks/" + id + "/approve", "", f.parent().bearer()).andExpect(status().isOk());

        afterDeadline();

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(balances.balance(f.teen().id()).balanceAmount())
                        .isEqualTo(100_00));
        assertNoPenaltyFor(f);
    }

    @Test
    void aCancelledTaskOrOneWithoutAPenaltyCostsNothing() throws Exception {
        Family f = fx.linkedFamily();
        UUID cancelled = created(f, 100_00, 150_00);
        created(f, 100_00, 0);
        fx.send("/tasks/" + cancelled + "/cancel", "", f.parent().bearer()).andExpect(status().isOk());

        afterDeadline();

        assertNoPenaltyFor(f);
        assertThat(balances.balance(f.teen().id()).balanceAmount()).isZero();
    }

    @Test
    void theOmittedPenaltyDefaultsToNone() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100_00);

        mvc.perform(get("/tasks/" + id).header("Authorization", f.teen().bearer()))
                .andExpect(jsonPath("$.penaltyAmount").value(0));
    }

    @Test
    void anInvalidPenaltyIsRefused() throws Exception {
        Family f = fx.linkedFamily();

        create(f, 100_00, -1)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_penalty"));
        create(f, 100_00, 10_000_001)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_penalty"));
    }

    @Test
    void aRecurringSeriesPassesItsPenaltyToEachOccurrence() throws Exception {
        Family f = fx.linkedFamily();
        Map<String, Object> body = new HashMap<>();
        body.put("teenId", f.teen().id());
        body.put("title", "Walk dog");
        body.put("rewardAmount", 100);
        body.put("penaltyAmount", 25);
        body.put("currency", "KZT");
        body.put("recurrence", "DAILY");
        body.put("dueTime", "23:59");
        fx.send("/task-series", json.writeValueAsString(body), f.parent().bearer())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.penaltyAmount").value(25));

        assertThat(jdbc.queryForList(
                        "SELECT penalty_amount FROM task.tasks WHERE teen_id = ?",
                        Long.class,
                        f.teen().id()))
                .allMatch(p -> p == 25);
    }
}
