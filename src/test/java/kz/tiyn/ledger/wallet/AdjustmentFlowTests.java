package kz.tiyn.ledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kz.tiyn.ledger.AdjustmentKind;
import kz.tiyn.ledger.BalanceAdjusted;
import kz.tiyn.ledger.BalanceReader;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import kz.tiyn.task.TaskFixtures.Person;
import kz.tiyn.task.TaskFixtures.Unjoined;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class AdjustmentFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    BalanceReader balances;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ApplicationEvents events;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    private String body(String kind, Object amount, String reason) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", kind);
        body.put("amount", amount);
        body.put("reason", reason);
        return json.writeValueAsString(body);
    }

    private ResultActions adjust(UUID teenId, String bearer, String key, String body) throws Exception {
        return fx.send("/teens/" + teenId + "/adjustments", body, bearer, "Idempotency-Key", key);
    }

    private JsonNode created(ResultActions result) throws Exception {
        return json.readTree(
                result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private List<Map<String, Object>> entries(UUID teenId) {
        return jdbc.queryForList(
                "SELECT id, kind, balance_delta, locked_delta, ref_id, note, actor_id FROM ledger.ledger_entries"
                        + " WHERE teen_id = ? ORDER BY created_at, id",
                teenId);
    }

    private long balanceOf(UUID teenId) {
        return balances.balance(teenId).balanceAmount();
    }

    @Test
    void aBonusCreditsTheWalletAndIsJournalledWithTheActorAndTheReason() throws Exception {
        Family f = fx.linkedFamily();

        JsonNode entry =
                created(adjust(f.teen().id(), f.parent().bearer(), "k1", body("BONUS", 250_00, "  Great grades ")));

        assertThat(entry.get("kind").asText()).isEqualTo("BONUS");
        assertThat(entry.get("amount").asLong()).isEqualTo(250_00);
        assertThat(entry.get("currency").asText()).isEqualTo("KZT");
        assertThat(entry.get("reason").asText()).isEqualTo("Great grades");
        assertThat(entry.get("teenId").asText()).isEqualTo(f.teen().id().toString());
        assertThat(entry.get("parentId").asText()).isEqualTo(f.parent().id().toString());
        assertThat(entry.get("createdAt").asText()).isNotBlank();
        assertThat(balanceOf(f.teen().id())).isEqualTo(250_00);
        mvc.perform(get("/balance").header("Authorization", f.teen().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAmount").value(250_00))
                .andExpect(jsonPath("$.availableAmount").value(250_00));
        assertThat(entries(f.teen().id())).singleElement().satisfies(row -> {
            assertThat(row.get("kind")).isEqualTo("MANUAL_BONUS");
            assertThat(row.get("balance_delta")).isEqualTo(250_00L);
            assertThat(row.get("locked_delta")).isEqualTo(0L);
            assertThat(row.get("ref_id")).isNull();
            assertThat(row.get("note")).isEqualTo("Great grades");
            assertThat(row.get("actor_id")).isEqualTo(f.parent().id());
            assertThat(row.get("id").toString()).isEqualTo(entry.get("id").asText());
        });
        assertThat(events.stream(BalanceAdjusted.class)
                        .filter(e -> e.teenId().equals(f.teen().id())))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.kind()).isEqualTo(AdjustmentKind.BONUS);
                    assertThat(e.amount()).isEqualTo(250_00);
                    assertThat(e.parentId()).isEqualTo(f.parent().id());
                    assertThat(e.entryId().toString()).isEqualTo(entry.get("id").asText());
                });
    }

    @Test
    void aPenaltyDebitsTheWalletEvenIntoDebtAndAnOppositeBonusCorrectsIt() throws Exception {
        Family f = fx.linkedFamily();

        JsonNode penalty =
                created(adjust(f.teen().id(), f.parent().bearer(), "k1", body("PENALTY", 300_00, "Broke a plate")));

        assertThat(penalty.get("kind").asText()).isEqualTo("PENALTY");
        assertThat(penalty.get("amount").asLong()).isEqualTo(300_00);
        assertThat(balanceOf(f.teen().id())).isEqualTo(-300_00);
        assertThat(balances.balance(f.teen().id()).availableAmount()).isEqualTo(-300_00);

        created(adjust(f.teen().id(), f.parent().bearer(), "k2", body("BONUS", 300_00, "Correction: it was the cat")));

        assertThat(balanceOf(f.teen().id())).isZero();
        assertThat(entries(f.teen().id()))
                .extracting(row -> row.get("kind"), row -> row.get("balance_delta"))
                .containsExactly(tuple("MANUAL_PENALTY", -300_00L), tuple("MANUAL_BONUS", 300_00L));
        assertThat(jdbc.queryForObject(
                        "SELECT balance_amount FROM ledger.wallets WHERE teen_id = ?",
                        Long.class,
                        f.teen().id()))
                .isEqualTo(jdbc.queryForObject(
                        "SELECT COALESCE(SUM(balance_delta), 0) FROM ledger.ledger_entries WHERE teen_id = ?",
                        Long.class,
                        f.teen().id()));
    }

    @Test
    void replayingTheSameKeyReturnsTheSameEntryAndAppliesItOnce() throws Exception {
        Family f = fx.linkedFamily();
        String payload = body("BONUS", 100_00, "Washed the car");

        JsonNode first = created(adjust(f.teen().id(), f.parent().bearer(), "same", payload));
        JsonNode second = created(adjust(f.teen().id(), f.parent().bearer(), "same", payload));

        assertThat(second).isEqualTo(first);
        assertThat(balanceOf(f.teen().id())).isEqualTo(100_00);
        assertThat(entries(f.teen().id())).hasSize(1);
        assertThat(events.stream(BalanceAdjusted.class)
                        .filter(e -> e.teenId().equals(f.teen().id())))
                .hasSize(1);
    }

    @Test
    void aReplayIgnoresWhitespaceDifferencesInTheReason() throws Exception {
        Family f = fx.linkedFamily();

        JsonNode first = created(adjust(f.teen().id(), f.parent().bearer(), "same", body("BONUS", 100_00, "Washed")));
        JsonNode second =
                created(adjust(f.teen().id(), f.parent().bearer(), "same", body("BONUS", 100_00, " Washed ")));

        assertThat(second.get("id")).isEqualTo(first.get("id"));
        assertThat(entries(f.teen().id())).hasSize(1);
    }

    @Test
    void reusingAKeyWithADifferentPayloadIsRejectedAndChangesNothing() throws Exception {
        Family f = fx.linkedFamily();
        created(adjust(f.teen().id(), f.parent().bearer(), "same", body("BONUS", 100_00, "Washed the car")));

        for (String changed : List.of(
                body("BONUS", 200_00, "Washed the car"),
                body("PENALTY", 100_00, "Washed the car"),
                body("BONUS", 100_00, "Something else"))) {
            adjust(f.teen().id(), f.parent().bearer(), "same", changed)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("invalid_idempotency_key"));
        }

        assertThat(balanceOf(f.teen().id())).isEqualTo(100_00);
        assertThat(entries(f.teen().id())).hasSize(1);
    }

    @Test
    void theSameKeyForAnotherTeenOfTheSameParentIsRejected() throws Exception {
        Unjoined unjoined = fx.unjoinedFamily();
        fx.join(unjoined);
        Family f = unjoined.family();
        Person sibling = fx.unlinkedTeen();
        fx.join(new Unjoined(new Family(f.parent(), sibling), unjoined.joinCode()));
        created(adjust(f.teen().id(), f.parent().bearer(), "same", body("BONUS", 100_00, "Washed")));

        adjust(sibling.id(), f.parent().bearer(), "same", body("BONUS", 100_00, "Washed"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_idempotency_key"));
        assertThat(entries(sibling.id())).isEmpty();
        created(adjust(sibling.id(), f.parent().bearer(), "other", body("BONUS", 100_00, "Washed")));
        assertThat(balanceOf(sibling.id())).isEqualTo(100_00);
    }

    @Test
    void theIdempotencyKeyIsRequired() throws Exception {
        Family f = fx.linkedFamily();

        fx.send(
                        "/teens/" + f.teen().id() + "/adjustments",
                        body("BONUS", 100_00, "Washed"),
                        f.parent().bearer())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_idempotency_key"));
        adjust(f.teen().id(), f.parent().bearer(), " ", body("BONUS", 100_00, "Washed"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_idempotency_key"));
        adjust(f.teen().id(), f.parent().bearer(), "k".repeat(101), body("BONUS", 100_00, "Washed"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(entries(f.teen().id())).isEmpty();
    }

    @Test
    void aTeenCannotAdjustAnyone() throws Exception {
        Family f = fx.linkedFamily();

        adjust(f.teen().id(), f.teen().bearer(), "k1", body("BONUS", 100_00, "Self service"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        assertThat(balanceOf(f.teen().id())).isZero();
    }

    @Test
    void aParentWhoIsNotLinkedToTheTeenIsForbidden() throws Exception {
        Family f = fx.linkedFamily();
        Family stranger = fx.linkedFamily();

        adjust(f.teen().id(), stranger.parent().bearer(), "k1", body("PENALTY", 100_00, "Not mine"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        assertThat(entries(f.teen().id())).isEmpty();
    }

    @Test
    void anUnknownTeenIsIndistinguishableFromAnUnlinkedOne() throws Exception {
        Family f = fx.linkedFamily();
        UUID unknown = UUID.randomUUID();

        adjust(unknown, f.parent().bearer(), "k1", body("BONUS", 100_00, "Ghost"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger.wallets WHERE teen_id = ?", Integer.class, unknown))
                .isZero();
    }

    @Test
    void aMalformedTeenIdIsABadRequest() throws Exception {
        Family f = fx.linkedFamily();

        fx.send(
                        "/teens/not-a-uuid/adjustments",
                        body("BONUS", 100_00, "x"),
                        f.parent().bearer(),
                        "Idempotency-Key",
                        "k1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void anUnauthenticatedCallIsRejected() throws Exception {
        Family f = fx.linkedFamily();

        adjust(f.teen().id(), null, "k1", body("BONUS", 100_00, "x")).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidPayloadsAreRejectedAndWriteNothing() throws Exception {
        Family f = fx.linkedFamily();
        String bearer = f.parent().bearer();

        adjust(f.teen().id(), bearer, "a1", body("BONUS", 0, "x"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_amount"));
        adjust(f.teen().id(), bearer, "a2", body("BONUS", -1, "x"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_amount"));
        adjust(f.teen().id(), bearer, "a3", body("BONUS", null, "x"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_amount"));
        adjust(f.teen().id(), bearer, "a4", body("BONUS", 10_000_001, "x"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_amount"));
        adjust(f.teen().id(), bearer, "a5", body("GIFT", 100, "x"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_kind"));
        adjust(f.teen().id(), bearer, "a6", body(null, 100, "x"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_kind"));
        adjust(f.teen().id(), bearer, "a7", body("BONUS", 100, ""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_reason"));
        adjust(f.teen().id(), bearer, "a8", body("BONUS", 100, "   "))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_reason"));
        adjust(f.teen().id(), bearer, "a9", body("BONUS", 100, null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_reason"));
        adjust(f.teen().id(), bearer, "a10", body("BONUS", 100, "x".repeat(141)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_reason"));
        adjust(f.teen().id(), bearer, "a11", "{not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_body"));

        assertThat(entries(f.teen().id())).isEmpty();
        assertThat(balanceOf(f.teen().id())).isZero();
    }

    @Test
    void aFailedValidationDoesNotBurnTheKey() throws Exception {
        Family f = fx.linkedFamily();
        adjust(f.teen().id(), f.parent().bearer(), "retry", body("BONUS", 0, "x"))
                .andExpect(status().isUnprocessableEntity());

        created(adjust(f.teen().id(), f.parent().bearer(), "retry", body("BONUS", 10, "x")));

        assertThat(balanceOf(f.teen().id())).isEqualTo(10);
    }

    @Test
    void theCapAmountIsAccepted() throws Exception {
        Family f = fx.linkedFamily();

        created(adjust(f.teen().id(), f.parent().bearer(), "cap", body("BONUS", 10_000_000, "Jackpot")));

        assertThat(balanceOf(f.teen().id())).isEqualTo(10_000_000);
    }

    @Test
    void anUnlinkedParentCannotReplayAnEarlierAdjustment() throws Exception {
        Family f = fx.linkedFamily();
        String payload = body("BONUS", 100_00, "Washed");
        created(adjust(f.teen().id(), f.parent().bearer(), "k1", payload));

        mvc.perform(delete("/links/" + f.teen().id())
                        .header("Authorization", f.parent().bearer()))
                .andExpect(status().isNoContent());

        adjust(f.teen().id(), f.parent().bearer(), "k1", payload).andExpect(status().isForbidden());
        adjust(f.teen().id(), f.parent().bearer(), "k2", payload).andExpect(status().isForbidden());
        assertThat(entries(f.teen().id())).hasSize(1);
    }

    @Test
    void theDatabaseRefusesAManualEntryWithoutAnActorOrAReason() throws Exception {
        Family f = fx.linkedFamily();
        created(adjust(f.teen().id(), f.parent().bearer(), "k1", body("BONUS", 100, "Seed")));
        String insert = "INSERT INTO ledger.ledger_entries"
                + " (id, teen_id, currency, kind, balance_delta, idempotency_key, note, actor_id)"
                + " VALUES (?, ?, 'KZT', 'MANUAL_BONUS', 5, ?, ?, ?)";

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), f.teen().id(), "raw-1", "why", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        insert,
                        UUID.randomUUID(),
                        f.teen().id(),
                        "raw-2",
                        null,
                        f.parent().id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE ledger.ledger_entries SET note = 'edited' WHERE teen_id = ?",
                        f.teen().id()))
                .isInstanceOf(DataAccessException.class);
        assertThat(entries(f.teen().id())).hasSize(1);
    }

    @Test
    void anAdjustmentDoesNotTouchLockedFundsOrOpenPayoutAccounting() throws Exception {
        Family f = fx.linkedFamily();
        created(adjust(f.teen().id(), f.parent().bearer(), "k1", body("BONUS", 500_00, "Start")));
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

        created(adjust(f.teen().id(), f.parent().bearer(), "k2", body("PENALTY", 100_00, "Late")));

        var balance = balances.balance(f.teen().id());
        assertThat(balance.balanceAmount()).isEqualTo(400_00);
        assertThat(balance.lockedAmount()).isEqualTo(100_00);
        assertThat(balance.availableAmount()).isEqualTo(300_00);
    }
}
