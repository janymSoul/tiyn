package kz.tiyn.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@AutoConfigureMockMvc
class PayoutFlowTests {

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
                .untilAsserted(() -> assertThat(balances.balance(f.teen().id()).approvedAmount())
                        .isEqualTo(reward));
        return f;
    }

    private ResultActions requestPayout(Family f, UUID parentId, Object amount, String key) throws Exception {
        String body =
                "{\"parentId\":" + (parentId == null ? "null" : "\"" + parentId + "\"") + ",\"amount\":" + amount + "}";
        return key == null
                ? fx.send("/payouts", body, f.teen().bearer())
                : fx.send("/payouts", body, f.teen().bearer(), "Idempotency-Key", key);
    }

    private UUID idOf(ResultActions result) throws Exception {
        return UUID.fromString(json.readTree(result.andReturn().getResponse().getContentAsString())
                .get("id")
                .asText());
    }

    @Test
    void aRequestHoldsBackTheAvailableBalanceButNotTheApprovedOne() throws Exception {
        Family f = richFamily(500_00);

        requestPayout(f, f.parent().id(), 300_00, "k1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("REQUESTED"))
                .andExpect(jsonPath("$.amount").value(300_00))
                .andExpect(jsonPath("$.parentId").value(f.parent().id().toString()));

        Balance balance = balances.balance(f.teen().id());
        assertThat(balance.approvedAmount()).isEqualTo(500_00);
        assertThat(balance.availableAmount()).isEqualTo(200_00);
    }

    @Test
    void repeatingTheSameKeyReturnsTheOriginalRequest() throws Exception {
        Family f = richFamily(500_00);

        UUID first = idOf(requestPayout(f, f.parent().id(), 300_00, "same").andExpect(status().isCreated()));
        UUID second = idOf(requestPayout(f, f.parent().id(), 300_00, "same").andExpect(status().isCreated()));

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ledger.payouts WHERE teen_id = ?",
                        Integer.class,
                        f.teen().id()))
                .isEqualTo(1);
    }

    @Test
    void aSecondOpenRequestIsRefused() throws Exception {
        Family f = richFamily(500_00);
        requestPayout(f, f.parent().id(), 100_00, "a").andExpect(status().isCreated());

        requestPayout(f, f.parent().id(), 100_00, "b")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("payout_already_open"));
    }

    @Test
    void theAmountIsCheckedAgainstTheAvailableBalanceAndTheMinimum() throws Exception {
        Family f = richFamily(500_00);

        requestPayout(f, f.parent().id(), 500_01, "a")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("insufficient_funds"));
        requestPayout(f, f.parent().id(), 99_99, "b")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("amount_too_small"));
        requestPayout(f, f.parent().id(), 0, "c")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_amount"));
        requestPayout(f, f.parent().id(), null, "d")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_amount"));
    }

    @Test
    void theKeyAndTheParentAreRequired() throws Exception {
        Family f = richFamily(500_00);

        requestPayout(f, f.parent().id(), 100_00, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_idempotency_key"));
        requestPayout(f, null, 100_00, "k")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("parent_required"));
    }

    @Test
    void onlyALinkedParentCanBeAsked() throws Exception {
        Family f = richFamily(500_00);
        Family other = fx.linkedFamily();

        requestPayout(f, other.parent().id(), 100_00, "x")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        requestPayout(f, UUID.randomUUID(), 100_00, "y").andExpect(status().isForbidden());
    }

    @Test
    void aParentCannotRequestAPayout() throws Exception {
        Family f = richFamily(500_00);

        fx.send(
                        "/payouts",
                        "{\"parentId\":\"" + f.parent().id() + "\",\"amount\":10000}",
                        f.parent().bearer(),
                        "Idempotency-Key",
                        "p")
                .andExpect(status().isForbidden());
    }

    @Test
    void fulfilingDebitsTheWalletOnceAndRecordsThePayoutEntry() throws Exception {
        Family f = richFamily(500_00);
        UUID id = idOf(requestPayout(f, f.parent().id(), 300_00, "f1"));

        fx.send("/payouts/" + id + "/fulfil", "", f.parent().bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FULFILLED"));
        fx.send("/payouts/" + id + "/fulfil", "", f.parent().bearer()).andExpect(status().isOk());

        Balance balance = balances.balance(f.teen().id());
        assertThat(balance.approvedAmount()).isEqualTo(200_00);
        assertThat(balance.availableAmount()).isEqualTo(200_00);
        assertThat(jdbc.queryForList(
                        "SELECT balance_delta FROM ledger.ledger_entries WHERE kind = 'PAYOUT' AND ref_id = ?",
                        Long.class,
                        id))
                .containsExactly(-300_00L);
        requestPayout(f, f.parent().id(), 100_00, "f2").andExpect(status().isCreated());
    }

    @Test
    void aCancelledRequestCannotBeFulfilled() throws Exception {
        Family f = richFamily(500_00);
        UUID id = idOf(requestPayout(f, f.parent().id(), 300_00, "g1"));
        fx.send("/payouts/" + id + "/cancel", "", f.teen().bearer()).andExpect(status().isOk());

        fx.send("/payouts/" + id + "/fulfil", "", f.parent().bearer())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("invalid_transition"));
        assertThat(balances.balance(f.teen().id()).approvedAmount()).isEqualTo(500_00);
    }

    @Test
    void onlyTheAddressedLinkedParentFulfils() throws Exception {
        Family f = richFamily(500_00);
        Family other = fx.linkedFamily();
        UUID id = idOf(requestPayout(f, f.parent().id(), 300_00, "h1"));

        fx.send("/payouts/" + id + "/fulfil", "", other.parent().bearer()).andExpect(status().isNotFound());
        fx.send("/payouts/" + id + "/fulfil", "", f.teen().bearer()).andExpect(status().isForbidden());
        assertThat(balances.balance(f.teen().id()).approvedAmount()).isEqualTo(500_00);
    }

    @Test
    void theTeenCancelsAndTheBalanceIsFreedAgain() throws Exception {
        Family f = richFamily(500_00);
        UUID id = idOf(requestPayout(f, f.parent().id(), 300_00, "c1"));

        fx.send("/payouts/" + id + "/cancel", "", f.teen().bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("TEEN_CANCELLED"));

        assertThat(balances.balance(f.teen().id()).availableAmount()).isEqualTo(500_00);
        fx.send("/payouts/" + id + "/cancel", "", f.teen().bearer()).andExpect(status().isOk());
        requestPayout(f, f.parent().id(), 100_00, "c2").andExpect(status().isCreated());
    }

    @Test
    void theParentDeclines() throws Exception {
        Family f = richFamily(500_00);
        UUID id = idOf(requestPayout(f, f.parent().id(), 300_00, "d1"));

        fx.send("/payouts/" + id + "/decline", "", f.parent().bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("DECLINED"));

        assertThat(balances.balance(f.teen().id()).availableAmount()).isEqualTo(500_00);
        fx.send("/payouts/" + id + "/cancel", "", f.teen().bearer())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("invalid_transition"));
    }

    @Test
    void strangersAndWrongRolesCannotTouchAPayout() throws Exception {
        Family f = richFamily(500_00);
        Family other = fx.linkedFamily();
        UUID id = idOf(requestPayout(f, f.parent().id(), 300_00, "s1"));

        fx.send("/payouts/" + id + "/decline", "", other.parent().bearer()).andExpect(status().isNotFound());
        fx.send("/payouts/" + id + "/cancel", "", other.teen().bearer()).andExpect(status().isNotFound());
        fx.send("/payouts/" + id + "/cancel", "", f.parent().bearer()).andExpect(status().isForbidden());
        fx.send("/payouts/" + id + "/decline", "", f.teen().bearer()).andExpect(status().isForbidden());
        mvc.perform(get("/payouts/" + id).header("Authorization", other.parent().bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/payouts/" + UUID.randomUUID())
                        .header("Authorization", f.teen().bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void eachSideListsOnlyItsOwnPayouts() throws Exception {
        Family f = richFamily(500_00);
        Family other = fx.linkedFamily();
        UUID id = idOf(requestPayout(f, f.parent().id(), 300_00, "l1"));

        mvc.perform(get("/payouts").header("Authorization", f.teen().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id.toString()));
        mvc.perform(get("/payouts?state=requested")
                        .header("Authorization", f.parent().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/payouts?state=fulfilled")
                        .header("Authorization", f.parent().bearer()))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/payouts").header("Authorization", other.parent().bearer()))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/payouts?state=bogus").header("Authorization", f.teen().bearer()))
                .andExpect(status().isUnprocessableEntity());
    }
}
