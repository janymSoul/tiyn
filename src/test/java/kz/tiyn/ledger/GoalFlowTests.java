package kz.tiyn.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@AutoConfigureMockMvc
class GoalFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    BalanceReader balances;

    @Autowired
    JdbcTemplate jdbc;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    private Family richFamily(long reward) throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, reward);
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"done\"}", f.teen().bearer(), "Idempotency-Key", "s-" + id)
                .andExpect(status().isOk());
        fx.send("/tasks/" + id + "/approve", "", f.parent().bearer()).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(balances.balance(f.teen().id()).balanceAmount())
                        .isEqualTo(reward));
        return f;
    }

    private UUID createGoal(Family f, String name, long target) throws Exception {
        String response = fx.send(
                        "/goals",
                        "{\"name\":\"" + name + "\",\"targetAmount\":" + target + "}",
                        f.teen().bearer())
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    private ResultActions move(Family f, UUID goal, String action, Object amount, String key) throws Exception {
        String body = "{\"amount\":" + amount + "}";
        return key == null
                ? fx.send("/goals/" + goal + "/" + action, body, f.teen().bearer())
                : fx.send("/goals/" + goal + "/" + action, body, f.teen().bearer(), "Idempotency-Key", key);
    }

    private void assertBalance(Family f, long balance, long locked, long available) {
        Balance b = balances.balance(f.teen().id());
        assertThat(b.balanceAmount()).isEqualTo(balance);
        assertThat(b.lockedAmount()).isEqualTo(locked);
        assertThat(b.availableAmount()).isEqualTo(available);
    }

    @Test
    void lockingEarmarksMoneyWithoutChangingTheTotal() throws Exception {
        Family f = richFamily(15_000_00);
        UUID goal = createGoal(f, "Headphones", 10_000_00);

        move(f, goal, "lock", 7_500_00, "a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedAmount").value(7_500_00))
                .andExpect(jsonPath("$.targetAmount").value(10_000_00))
                .andExpect(jsonPath("$.state").value("ACTIVE"));

        assertBalance(f, 15_000_00, 7_500_00, 7_500_00);
        mvc.perform(get("/goals").header("Authorization", f.teen().bearer()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].savedAmount").value(7_500_00));
    }

    @Test
    void earmarkedMoneyCannotBeRequestedAsAPayout() throws Exception {
        Family f = richFamily(15_000_00);
        UUID goal = createGoal(f, "Headphones", 10_000_00);
        move(f, goal, "lock", 7_500_00, "a").andExpect(status().isOk());

        String tooMuch = "{\"parentId\":\"" + f.parent().id() + "\",\"amount\":" + 7_500_01 + "}";
        fx.send("/payouts", tooMuch, f.teen().bearer(), "Idempotency-Key", "p1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("insufficient_funds"));
        String fits = "{\"parentId\":\"" + f.parent().id() + "\",\"amount\":" + 7_500_00 + "}";
        fx.send("/payouts", fits, f.teen().bearer(), "Idempotency-Key", "p2").andExpect(status().isCreated());
    }

    @Test
    void severalGoalsAddUpAndUnlockingReturnsMoneyToAvailable() throws Exception {
        Family f = richFamily(15_000_00);
        UUID headphones = createGoal(f, "Headphones", 10_000_00);
        UUID bike = createGoal(f, "Bike", 5_000_00);

        move(f, headphones, "lock", 7_500_00, "a").andExpect(status().isOk());
        move(f, bike, "lock", 3_000_00, "b").andExpect(status().isOk());
        assertBalance(f, 15_000_00, 10_500_00, 4_500_00);

        move(f, headphones, "unlock", 2_500_00, "c")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedAmount").value(5_000_00));
        assertBalance(f, 15_000_00, 8_000_00, 7_000_00);
    }

    @Test
    void theRulesAreEnforced() throws Exception {
        Family f = richFamily(15_000_00);
        UUID goal = createGoal(f, "Headphones", 10_000_00);
        UUID big = createGoal(f, "Car", 100_000_00);

        move(f, goal, "lock", 10_000_01, "a")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("exceeds_target"));
        move(f, big, "lock", 15_000_01, "b")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("insufficient_funds"));
        move(f, goal, "unlock", 1, "c")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("insufficient_goal_funds"));
        move(f, goal, "lock", 0, "d")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_amount"));
        move(f, goal, "lock", 100, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_idempotency_key"));
        assertBalance(f, 15_000_00, 0, 15_000_00);
    }

    @Test
    void repeatingACreateWithTheSameKeyReturnsTheSameGoalAndADifferentRequestIsRefused() throws Exception {
        Family f = fx.linkedFamily();
        String body = "{\"name\":\"Bike\",\"targetAmount\":500}";

        String first = fx.send("/goals", body, f.teen().bearer(), "Idempotency-Key", "g-1")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        fx.send("/goals", body, f.teen().bearer(), "Idempotency-Key", "g-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(json.readTree(first).get("id").asText()));
        fx.send("/goals", "{\"name\":\"Bike\",\"targetAmount\":900}", f.teen().bearer(), "Idempotency-Key", "g-1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_idempotency_key"));

        mvc.perform(get("/goals").header("Authorization", f.teen().bearer()))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void creatingAGoalWithoutAKeyIsRefused() throws Exception {
        Family f = fx.linkedFamily();

        mvc.perform(post("/goals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", f.teen().bearer())
                        .content("{\"name\":\"Bike\",\"targetAmount\":500}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_idempotency_key"));
    }

    @Test
    void creatingAGoalValidatesItsInput() throws Exception {
        Family f = fx.linkedFamily();

        fx.send("/goals", "{\"name\":\" \",\"targetAmount\":100}", f.teen().bearer())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_name"));
        fx.send("/goals", "{\"name\":\"x\",\"targetAmount\":0}", f.teen().bearer())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_amount"));
        fx.send("/goals", "{\"name\":\"x\"}", f.teen().bearer())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_amount"));
    }

    @Test
    void repeatingAKeyDoesNotLockTwiceAndADifferentRequestIsRefused() throws Exception {
        Family f = richFamily(15_000_00);
        UUID goal = createGoal(f, "Headphones", 10_000_00);
        UUID other = createGoal(f, "Bike", 5_000_00);

        move(f, goal, "lock", 1_000_00, "same").andExpect(status().isOk());
        move(f, goal, "lock", 1_000_00, "same")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedAmount").value(1_000_00));
        move(f, goal, "lock", 2_000_00, "same")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_idempotency_key"));
        move(f, other, "lock", 1_000_00, "same")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_idempotency_key"));
        assertBalance(f, 15_000_00, 1_000_00, 14_000_00);
    }

    @Test
    void closingReleasesWhatWasSavedAndRepeatingIsANoOp() throws Exception {
        Family f = richFamily(15_000_00);
        UUID goal = createGoal(f, "Headphones", 10_000_00);
        move(f, goal, "lock", 4_000_00, "a").andExpect(status().isOk());

        fx.send("/goals/" + goal + "/close", "", f.teen().bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CLOSED"))
                .andExpect(jsonPath("$.savedAmount").value(0));
        fx.send("/goals/" + goal + "/close", "", f.teen().bearer()).andExpect(status().isOk());

        assertBalance(f, 15_000_00, 0, 15_000_00);
        move(f, goal, "lock", 100, "b")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("goal_closed"));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ledger.ledger_entries WHERE ref_id = ? AND kind = 'GOAL_UNLOCK'",
                        Integer.class,
                        goal))
                .isEqualTo(1);
    }

    @Test
    void goalsAreTeenOnlyAndPrivate() throws Exception {
        Family f = richFamily(15_000_00);
        Family other = fx.linkedFamily();
        UUID goal = createGoal(f, "Headphones", 10_000_00);

        fx.send("/goals", "{\"name\":\"x\",\"targetAmount\":100}", f.parent().bearer())
                .andExpect(status().isForbidden());
        mvc.perform(get("/goals").header("Authorization", f.parent().bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/goals/" + goal).header("Authorization", other.teen().bearer()))
                .andExpect(status().isNotFound());
        move(other, goal, "lock", 100, "z").andExpect(status().isNotFound());
        fx.send("/goals/" + goal + "/close", "", other.teen().bearer()).andExpect(status().isNotFound());
        mvc.perform(get("/goals/" + goal).header("Authorization", f.teen().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Headphones"));
    }
}
