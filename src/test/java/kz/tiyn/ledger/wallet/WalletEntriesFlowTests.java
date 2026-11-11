package kz.tiyn.ledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
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
class WalletEntriesFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    private void entry(UUID teenId, String kind, long balanceDelta, long lockedDelta, UUID refId, int minutesAgo) {
        jdbc.update("INSERT INTO ledger.wallets (teen_id, currency) VALUES (?, 'KZT') ON CONFLICT DO NOTHING", teenId);
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO ledger.ledger_entries
                    (id, teen_id, currency, kind, balance_delta, locked_delta, ref_id, idempotency_key, created_at)
                VALUES (?, ?, 'KZT', ?, ?, ?, ?, ?, ?)
                """,
                id,
                teenId,
                kind,
                balanceDelta,
                lockedDelta,
                refId,
                "history-" + id,
                Timestamp.from(Instant.now().minus(minutesAgo, ChronoUnit.MINUTES)));
    }

    private ResultActions list(String bearer, String query) throws Exception {
        return mvc.perform(get("/wallet/entries" + query).header("Authorization", bearer));
    }

    private JsonNode items(String bearer, String query) throws Exception {
        String body = list(bearer, query)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body).get("items");
    }

    @Test
    void everyKindShowsItsEffectOnAvailableAndItsRelatedId() throws Exception {
        Family f = fx.linkedFamily();
        UUID teenId = f.teen().id();
        UUID task = UUID.randomUUID();
        UUID penalty = UUID.randomUUID();
        UUID goal = UUID.randomUUID();
        UUID payout = UUID.randomUUID();
        entry(teenId, "TASK_REWARD", 50_000, 0, task, 5);
        entry(teenId, "GOAL_LOCK", 0, 20_000, goal, 4);
        entry(teenId, "GOAL_UNLOCK", 0, -5_000, goal, 3);
        entry(teenId, "PENALTY", -1_000, 0, penalty, 2);
        entry(teenId, "PAYOUT", -10_000, 0, payout, 1);

        JsonNode items = items(f.teen().bearer(), "");

        assertThat(items).hasSize(5);
        assertThat(items.get(0).get("kind").asText()).isEqualTo("PAYOUT");
        assertThat(items.get(0).get("amount").asLong()).isEqualTo(-10_000);
        assertThat(items.get(0).get("payoutId").asText()).isEqualTo(payout.toString());
        assertThat(items.get(0).has("taskId")).isFalse();
        assertThat(items.get(0).has("goalId")).isFalse();
        assertThat(items.get(1).get("kind").asText()).isEqualTo("PENALTY");
        assertThat(items.get(1).get("amount").asLong()).isEqualTo(-1_000);
        assertThat(items.get(1).get("taskId").asText()).isEqualTo(penalty.toString());
        assertThat(items.get(2).get("kind").asText()).isEqualTo("GOAL_UNLOCK");
        assertThat(items.get(2).get("amount").asLong()).isEqualTo(5_000);
        assertThat(items.get(2).get("goalId").asText()).isEqualTo(goal.toString());
        assertThat(items.get(3).get("kind").asText()).isEqualTo("GOAL_LOCK");
        assertThat(items.get(3).get("amount").asLong()).isEqualTo(-20_000);
        assertThat(items.get(4).get("kind").asText()).isEqualTo("TASK_REWARD");
        assertThat(items.get(4).get("amount").asLong()).isEqualTo(50_000);
        assertThat(items.get(4).get("taskId").asText()).isEqualTo(task.toString());
        assertThat(items.get(4).get("currency").asText()).isEqualTo("KZT");
        assertThat(Instant.parse(items.get(4).get("createdAt").asText())).isBefore(Instant.now());
    }

    @Test
    void anOrdinaryPenaltyEntryWithoutAnyReferenceHasNoRelatedId() throws Exception {
        Family f = fx.linkedFamily();
        entry(f.teen().id(), "PENALTY", -1_000, 0, null, 1);

        JsonNode item = items(f.teen().bearer(), "").get(0);

        assertThat(item.has("taskId")).isFalse();
        assertThat(item.has("payoutId")).isFalse();
        assertThat(item.has("goalId")).isFalse();
    }

    @Test
    void aTeenWithoutAWalletGetsAnEmptyList() throws Exception {
        Family f = fx.linkedFamily();

        list(f.teen().bearer(), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void aTeenMayNameThemselves() throws Exception {
        Family f = fx.linkedFamily();
        entry(f.teen().id(), "TASK_REWARD", 100, 0, UUID.randomUUID(), 1);

        assertThat(items(f.teen().bearer(), "?teenId=" + f.teen().id())).hasSize(1);
    }

    @Test
    void aTeenNamingAnotherTeenIsForbiddenAndSeesNothing() throws Exception {
        Family mine = fx.linkedFamily();
        Family other = fx.linkedFamily();
        entry(other.teen().id(), "TASK_REWARD", 100, 0, UUID.randomUUID(), 1);

        list(mine.teen().bearer(), "?teenId=" + other.teen().id())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void aTeenNeverSeesAnotherTeensEntries() throws Exception {
        Family mine = fx.linkedFamily();
        Family other = fx.linkedFamily();
        entry(other.teen().id(), "TASK_REWARD", 100, 0, UUID.randomUUID(), 1);

        assertThat(items(mine.teen().bearer(), "")).isEmpty();
    }

    @Test
    void aLinkedParentReadsTheTeensHistory() throws Exception {
        Family f = fx.linkedFamily();
        entry(f.teen().id(), "TASK_REWARD", 700, 0, UUID.randomUUID(), 1);

        JsonNode items = items(f.parent().bearer(), "?teenId=" + f.teen().id());

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("amount").asLong()).isEqualTo(700);
    }

    @Test
    void aParentWithoutATeenIdIsAskedForOne() throws Exception {
        Family f = fx.linkedFamily();

        list(f.parent().bearer(), "")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("teen_required"));
    }

    @Test
    void aParentCannotReadAnUnlinkedTeensHistory() throws Exception {
        Family mine = fx.linkedFamily();
        Family other = fx.linkedFamily();
        entry(other.teen().id(), "TASK_REWARD", 100, 0, UUID.randomUUID(), 1);

        list(mine.parent().bearer(), "?teenId=" + other.teen().id())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void aMalformedTeenIdIsABadRequest() throws Exception {
        Family f = fx.linkedFamily();

        list(f.parent().bearer(), "?teenId=nope")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void anApprovedTaskAppearsAsARewardLinkedToTheTask() throws Exception {
        Family f = fx.linkedFamily();
        UUID task = fx.createTask(f, 500_00);
        fx.send("/tasks/" + task + "/submit", "{\"text\":\"done\"}", f.teen().bearer(), "Idempotency-Key", "h1")
                .andExpect(status().isOk());
        fx.send("/tasks/" + task + "/approve", "", f.parent().bearer()).andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(items(f.teen().bearer(), "")).hasSize(1));

        JsonNode reward = items(f.teen().bearer(), "").get(0);
        assertThat(reward.get("kind").asText()).isEqualTo("TASK_REWARD");
        assertThat(reward.get("amount").asLong()).isEqualTo(500_00);
        assertThat(reward.get("taskId").asText()).isEqualTo(task.toString());
    }
}
