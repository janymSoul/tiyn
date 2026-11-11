package kz.tiyn.ledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import kz.tiyn.task.TaskFixtures.Person;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@AutoConfigureMockMvc
class ManualAdjustmentAcrossFeaturesTests {

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

    private ResultActions adjust(Person parent, UUID teenId, String key, String kind, long amount, String reason)
            throws Exception {
        String body = json.writeValueAsString(
                json.createObjectNode().put("kind", kind).put("amount", amount).put("reason", reason));
        return fx.send("/teens/" + teenId + "/adjustments", body, parent.bearer(), "Idempotency-Key", key);
    }

    private JsonNode adjusted(Person parent, UUID teenId, String key, String kind, long amount, String reason)
            throws Exception {
        String body = adjust(parent, teenId, key, kind, amount, reason)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body);
    }

    private JsonNode read(String path, String bearer) throws Exception {
        String body = mvc.perform(get(path).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body);
    }

    private void insertReward(UUID teenId, int minutesAgo) {
        jdbc.update("INSERT INTO ledger.wallets (teen_id, currency) VALUES (?, 'KZT') ON CONFLICT DO NOTHING", teenId);
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO ledger.ledger_entries
                    (id, teen_id, currency, kind, balance_delta, locked_delta, ref_id, idempotency_key, created_at)
                VALUES (?, ?, 'KZT', 'TASK_REWARD', 1000, 0, ?, ?, ?)
                """,
                id,
                teenId,
                UUID.randomUUID(),
                "reward-" + id,
                Timestamp.from(Instant.now().minus(minutesAgo, ChronoUnit.MINUTES)));
    }

    private int ledgerRows(UUID teenId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger.ledger_entries WHERE teen_id = ?", Integer.class, teenId);
    }

    private void deleteAccount(Person person) throws Exception {
        mvc.perform(delete("/me").header("Authorization", person.bearer())).andExpect(status().isNoContent());
    }

    private void assertHistory(String bearer, String path, UUID parentId) throws Exception {
        JsonNode first = read(path, bearer);
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("nextCursor").asText()).isNotBlank();
        JsonNode helped = first.at("/items/0");
        assertThat(helped.get("kind").asText()).isEqualTo("MANUAL_BONUS");
        assertThat(helped.get("amount").asLong()).isEqualTo(50_00);
        assertThat(helped.get("reason").asText()).isEqualTo("Helped with the groceries");
        assertThat(helped.get("actorId").asText()).isEqualTo(parentId.toString());
        for (String related : new String[] {"taskId", "payoutId", "goalId"}) {
            assertThat(helped.has(related)).isFalse();
        }
        JsonNode plate = first.at("/items/1");
        assertThat(plate.get("kind").asText()).isEqualTo("MANUAL_PENALTY");
        assertThat(plate.get("amount").asLong()).isEqualTo(-100_00);
        assertThat(plate.get("reason").asText()).isEqualTo("Broke a plate");
        assertThat(plate.get("actorId").asText()).isEqualTo(parentId.toString());

        JsonNode second = read(path + "&cursor=" + first.get("nextCursor").asText(), bearer);
        assertThat(second.get("items")).hasSize(2);
        assertThat(second.at("/items/0/kind").asText()).isEqualTo("MANUAL_BONUS");
        assertThat(second.at("/items/0/amount").asLong()).isEqualTo(250_00);
        assertThat(second.at("/items/0/reason").asText()).isEqualTo("Great grades");
        JsonNode reward = second.at("/items/1");
        assertThat(reward.get("kind").asText()).isEqualTo("TASK_REWARD");
        assertThat(reward.has("reason")).isFalse();
        assertThat(reward.has("actorId")).isFalse();
        assertThat(second.has("nextCursor")).isFalse();
    }

    private Family familyWithHistory() throws Exception {
        Family f = fx.linkedFamily();
        insertReward(f.teen().id(), 10);
        adjusted(f.parent(), f.teen().id(), "h1", "BONUS", 250_00, "Great grades");
        adjusted(f.parent(), f.teen().id(), "h2", "PENALTY", 100_00, "Broke a plate");
        adjusted(f.parent(), f.teen().id(), "h3", "BONUS", 50_00, "Helped with the groceries");
        return f;
    }

    @Test
    void bothManualKindsShowSignedAmountReasonAndParentInTheTeensHistoryAcrossAPageBoundary() throws Exception {
        Family f = familyWithHistory();

        assertHistory(f.teen().bearer(), "/wallet/entries?limit=2", f.parent().id());
    }

    @Test
    void theLinkedParentSeesTheSameManualEntriesInTheTeensHistoryAcrossAPageBoundary() throws Exception {
        Family f = familyWithHistory();

        assertHistory(
                f.parent().bearer(),
                "/wallet/entries?limit=2&teenId=" + f.teen().id(),
                f.parent().id());
    }

    @Test
    void theTeensExportListsManualEntriesWithReasonAndActorAndTheParentsExportHasNoLedger() throws Exception {
        Family f = fx.linkedFamily();
        adjusted(f.parent(), f.teen().id(), "e1", "BONUS", 250_00, "Great grades");
        adjusted(f.parent(), f.teen().id(), "e2", "PENALTY", 100_00, "Broke a plate");

        JsonNode teen = read("/me/export", f.teen().bearer());
        JsonNode parent = read("/me/export", f.parent().bearer());

        assertThat(teen.get("ledgerEntries")).hasSize(2);
        JsonNode penalty = teen.at("/ledgerEntries/0");
        assertThat(penalty.get("kind").asText()).isEqualTo("MANUAL_PENALTY");
        assertThat(penalty.get("balanceDelta").asLong()).isEqualTo(-100_00);
        assertThat(penalty.get("note").asText()).isEqualTo("Broke a plate");
        assertThat(penalty.get("actorId").asText()).isEqualTo(f.parent().id().toString());
        assertThat(penalty.has("refId")).isFalse();
        JsonNode bonus = teen.at("/ledgerEntries/1");
        assertThat(bonus.get("kind").asText()).isEqualTo("MANUAL_BONUS");
        assertThat(bonus.get("balanceDelta").asLong()).isEqualTo(250_00);
        assertThat(bonus.get("note").asText()).isEqualTo("Great grades");
        assertThat(parent.has("ledgerEntries")).isFalse();
        assertThat(parent.toString()).doesNotContain("Great grades").doesNotContain("Broke a plate");
    }

    @Test
    void aDeletedParentCanNoLongerPostAndTheirEarlierEntriesStayIntactInHistoryAndExport() throws Exception {
        Family f = fx.linkedFamily();
        adjusted(f.parent(), f.teen().id(), "d1", "BONUS", 250_00, "Great grades");

        deleteAccount(f.parent());

        adjust(f.parent(), f.teen().id(), "d1", "BONUS", 250_00, "Great grades")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthenticated"));
        adjust(f.parent(), f.teen().id(), "d2", "PENALTY", 10_00, "Late").andExpect(status().isUnauthorized());
        assertThat(ledgerRows(f.teen().id())).isEqualTo(1);
        JsonNode history = read("/wallet/entries", f.teen().bearer());
        assertThat(history.get("items")).hasSize(1);
        assertThat(history.at("/items/0/kind").asText()).isEqualTo("MANUAL_BONUS");
        assertThat(history.at("/items/0/reason").asText()).isEqualTo("Great grades");
        assertThat(history.at("/items/0/actorId").asText())
                .isEqualTo(f.parent().id().toString());
        JsonNode export = read("/me/export", f.teen().bearer());
        assertThat(export.at("/ledgerEntries/0/actorId").asText())
                .isEqualTo(f.parent().id().toString());
        assertThat(export.at("/ledgerEntries/0/note").asText()).isEqualTo("Great grades");
        mvc.perform(get("/balance").header("Authorization", f.teen().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAmount").value(250_00));
    }

    @Test
    void anAdjustmentForADeletedTeenIsForbiddenTheirHistoryIsUnreachableAndTheRowsStay() throws Exception {
        Family f = fx.linkedFamily();
        adjusted(f.parent(), f.teen().id(), "t1", "PENALTY", 40_00, "Late");

        deleteAccount(f.teen());

        adjust(f.parent(), f.teen().id(), "t2", "BONUS", 10_00, "Sorry")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mvc.perform(get("/wallet/entries?teenId=" + f.teen().id())
                        .header("Authorization", f.parent().bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/wallet/entries").header("Authorization", f.teen().bearer()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/me/export").header("Authorization", f.teen().bearer()))
                .andExpect(status().isUnauthorized());
        assertThat(ledgerRows(f.teen().id())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT balance_amount FROM ledger.wallets WHERE teen_id = ?",
                        Long.class,
                        f.teen().id()))
                .isEqualTo(-40_00);
    }

    @Test
    void anAdjustmentKeyIsScopedToTheParentAndDoesNotTouchThePlatformKeyTable() throws Exception {
        Family f = fx.linkedFamily();
        Family other = fx.linkedFamily();

        JsonNode mine = adjusted(f.parent(), f.teen().id(), "shared-key", "BONUS", 10_00, "Mine");
        JsonNode replay = adjusted(f.parent(), f.teen().id(), "shared-key", "BONUS", 10_00, "Mine");
        JsonNode theirs = adjusted(other.parent(), other.teen().id(), "shared-key", "BONUS", 10_00, "Mine");

        assertThat(replay).isEqualTo(mine);
        assertThat(theirs.get("id")).isNotEqualTo(mine.get("id"));
        assertThat(ledgerRows(f.teen().id())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.idempotency_keys WHERE user_id = ?",
                        Integer.class,
                        f.parent().id()))
                .isZero();
    }

    @Test
    void aReplayStillReturnsTheFirstEntryAfterTheCallersOtherKeysHaveLongBeenCleanedUp() throws Exception {
        Family f = fx.linkedFamily();
        JsonNode first = adjusted(f.parent(), f.teen().id(), "old", "BONUS", 30_00, "Washed the car");
        jdbc.update(
                "DELETE FROM platform.idempotency_keys WHERE user_id = ?",
                f.parent().id());

        JsonNode replay = adjusted(f.parent(), f.teen().id(), "old", "BONUS", 30_00, "Washed the car");
        adjust(f.parent(), f.teen().id(), "old", "BONUS", 31_00, "Washed the car")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_idempotency_key"));

        assertThat(replay).isEqualTo(first);
        assertThat(ledgerRows(f.teen().id())).isEqualTo(1);
        assertThat(read("/balance", f.teen().bearer()).get("balanceAmount").asLong())
                .isEqualTo(30_00);
    }

    @Test
    void bothDashboardsReflectABonusAndAPenaltyThatLeavesTheTeenInDebt() throws Exception {
        Family f = fx.linkedFamily();

        adjusted(f.parent(), f.teen().id(), "m1", "BONUS", 500_00, "Great grades");
        JsonNode afterBonus = read("/dashboard", f.teen().bearer());
        assertThat(afterBonus.at("/balance/balanceAmount").asLong()).isEqualTo(500_00);
        assertThat(afterBonus.at("/balance/availableAmount").asLong()).isEqualTo(500_00);
        adjusted(f.parent(), f.teen().id(), "m2", "PENALTY", 800_00, "Lost the phone");

        JsonNode teenView = read("/dashboard", f.teen().bearer());
        JsonNode balance = read("/balance", f.teen().bearer());
        JsonNode parentView = read("/parent/dashboard", f.parent().bearer());
        assertThat(teenView.at("/balance/balanceAmount").asLong()).isEqualTo(-300_00);
        assertThat(teenView.at("/balance/availableAmount").asLong()).isEqualTo(-300_00);
        assertThat(balance.get("balanceAmount").asLong()).isEqualTo(-300_00);
        assertThat(balance.get("availableAmount").asLong()).isEqualTo(-300_00);
        assertThat(parentView.at("/teens/0/id").asText())
                .isEqualTo(f.teen().id().toString());
        assertThat(parentView.at("/teens/0/balance/balanceAmount").asLong()).isEqualTo(-300_00);
        assertThat(parentView.at("/teens/0/balance/availableAmount").asLong()).isEqualTo(-300_00);
    }
}
