package kz.tiyn.identity;

import static kz.tiyn.support.FakeGoogleConfig.tokenFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import kz.tiyn.ledger.BalanceReader;
import kz.tiyn.platform.security.Role;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import kz.tiyn.task.TaskFixtures.Person;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class AccountDeletionFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    BalanceReader balances;

    @Autowired
    ApplicationEvents events;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    @Test
    void deletingAParentUnlinksTheirTeensCancelsTheTeensWorkAndKillsTheirToken() throws Exception {
        Family f = fx.linkedFamily();
        UUID open = fx.createTask(f, 100);
        UUID submitted = fx.createTask(f, 100);
        fx.send("/tasks/" + submitted + "/submit", "{\"text\":\"a\"}", f.teen().bearer(), "Idempotency-Key", "d1");
        UUID series = createSeries(f);

        deleteMe(f.parent()).andExpect(status().isNoContent());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(taskState(open)).isEqualTo("CANCELLED");
            assertThat(taskState(submitted)).isEqualTo("CANCELLED");
            assertThat(seriesActive(series)).isFalse();
        });
        mvc.perform(get("/family").header("Authorization", f.teen().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(0));
        assertThat(activeLinks("parent_id", f.parent().id())).isZero();
        assertThat(events.stream(FamilyUnlinked.class)
                        .filter(e -> e.parentId().equals(f.parent().id()))
                        .count())
                .isEqualTo(1);
        assertThat(events.stream(AccountDeleted.class)
                        .filter(e -> e.userId().equals(f.parent().id()))
                        .map(AccountDeleted::role))
                .containsExactly(Role.PARENT);
    }

    @Test
    void aDeletedAccountsStillUnexpiredTokenIsRejectedEverywhereAndDeletingAgainIsUnauthenticated() throws Exception {
        Family f = fx.linkedFamily();

        deleteMe(f.parent()).andExpect(status().isNoContent());

        for (var request : new ResultActions[] {
            mvc.perform(get("/me").header("Authorization", f.parent().bearer())),
            mvc.perform(get("/family").header("Authorization", f.parent().bearer())),
            deleteMe(f.parent())
        }) {
            request.andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthenticated"));
        }
        mvc.perform(get("/me").header("Authorization", f.teen().bearer())).andExpect(status().isOk());
    }

    @Test
    void theAnonymisedRowKeepsItsIdAndRoleButNoNameIdentityKeyOrJoinCode() throws Exception {
        Family f = fx.linkedFamily();

        deleteMe(f.parent()).andExpect(status().isNoContent());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM identity.users WHERE id = ?", f.parent().id());
        assertThat(row.get("display_name")).isEqualTo("Deleted user");
        assertThat(row.get("idp_sub")).isEqualTo("deleted:" + f.parent().id());
        assertThat(row.get("join_code")).isNull();
        assertThat(row.get("role")).isEqualTo("PARENT");
        assertThat(row.get("deleted_at")).isNotNull();
        Map<String, Object> untouched = jdbc.queryForMap(
                "SELECT * FROM identity.users WHERE id = ?", f.teen().id());
        assertThat(untouched.get("display_name")).isEqualTo("Dana");
        assertThat(untouched.get("deleted_at")).isNull();
    }

    @Test
    void theSameGoogleAccountCanRegisterAgainAsABrandNewUserAndTheOldJoinCodeIsDead() throws Exception {
        String sub = "sub-" + UUID.randomUUID();
        JsonNode first = signIn(sub, "PARENT", "Aigul");
        String firstId = first.at("/user/id").asText();
        String oldCode = first.at("/user/joinCode").asText();
        deleteMe(bearerOf(first)).andExpect(status().isNoContent());

        JsonNode second = signIn(sub, "PARENT", "Aigul Again");

        assertThat(second.at("/user/id").asText()).isNotEqualTo(firstId);
        assertThat(second.at("/user/displayName").asText()).isEqualTo("Aigul Again");
        Person newcomer = fx.unlinkedTeen();
        fx.send("/links/join", "{\"code\":\"" + oldCode + "\"}", newcomer.bearer())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_join_code"));
        fx.send("/links/join", "{\"code\":\"" + second.at("/user/joinCode").asText() + "\"}", newcomer.bearer())
                .andExpect(status().isOk());
    }

    @Test
    void deletingATeenKeepsTheirPayoutHistoryForTheFormerParentRemovesDevicesAndKillsTheToken() throws Exception {
        Family f = richFamily(500_00);
        UUID payout = requestPayout(f, 300_00, "p1");
        fx.send("/payouts/" + payout + "/fulfil", "", f.parent().bearer()).andExpect(status().isOk());
        String device = "device-" + UUID.randomUUID();
        registerDevice(f.teen(), device);

        deleteMe(f.teen()).andExpect(status().isNoContent());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM notification.device_tokens WHERE user_id = ?",
                        Integer.class,
                        f.teen().id()))
                .isZero());
        mvc.perform(get("/me").header("Authorization", f.teen().bearer())).andExpect(status().isUnauthorized());
        mvc.perform(get("/family").header("Authorization", f.parent().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(0));
        mvc.perform(get("/payouts").header("Authorization", f.parent().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].teenId").value(f.teen().id().toString()))
                .andExpect(jsonPath("$.items[0].state").value("FULFILLED"));
        assertThat(jdbc.queryForObject(
                        "SELECT display_name FROM identity.users WHERE id = ?",
                        String.class,
                        f.teen().id()))
                .isEqualTo("Deleted user");
    }

    @Test
    void deletingATeenWithTwoParentsUnlinksBoth() throws Exception {
        Family first = fx.linkedFamily();
        Person secondParent = fx.linkedFamily().parent();
        fx.send(
                        "/links/join",
                        "{\"code\":\"" + joinCodeOf(secondParent.id()) + "\"}",
                        first.teen().bearer())
                .andExpect(status().isOk());

        deleteMe(first.teen()).andExpect(status().isNoContent());

        assertThat(activeLinks("teen_id", first.teen().id())).isZero();
        assertThat(events.stream(FamilyUnlinked.class)
                        .filter(e -> e.teenId().equals(first.teen().id()))
                        .count())
                .isEqualTo(2);
    }

    @Test
    void anOpenPayoutBlocksDeletionForTheTeenAndForTheAddressedParentUntilItIsResolved() throws Exception {
        Family f = richFamily(500_00);
        UUID payout = requestPayout(f, 200_00, "p2");

        deleteMe(f.teen())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("account_has_open_payout"));
        deleteMe(f.parent())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("account_has_open_payout"));
        mvc.perform(get("/me").header("Authorization", f.teen().bearer())).andExpect(status().isOk());
        mvc.perform(get("/me").header("Authorization", f.parent().bearer())).andExpect(status().isOk());
        assertThat(activeLinks("parent_id", f.parent().id())).isEqualTo(1);

        fx.send("/payouts/" + payout + "/decline", "", f.parent().bearer()).andExpect(status().isOk());

        deleteMe(f.parent()).andExpect(status().isNoContent());
        deleteMe(f.teen()).andExpect(status().isNoContent());
    }

    @Test
    void aRemainingBalanceDoesNotBlockDeletion() throws Exception {
        Family f = richFamily(500_00);

        deleteMe(f.teen()).andExpect(status().isNoContent());

        assertThat(balances.balance(f.teen().id()).balanceAmount()).isEqualTo(500_00);
    }

    @Test
    void aParentWithOnlyUnrelatedOpenPayoutsOfOtherParentsCanStillDelete() throws Exception {
        Family busy = richFamily(500_00);
        requestPayout(busy, 100_00, "p3");
        Family idle = fx.linkedFamily();

        deleteMe(idle.parent()).andExpect(status().isNoContent());
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

    private UUID requestPayout(Family f, long amount, String key) throws Exception {
        String body = "{\"parentId\":\"" + f.parent().id() + "\",\"amount\":" + amount + "}";
        String response = fx.send("/payouts", body, f.teen().bearer(), "Idempotency-Key", key)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    private void registerDevice(Person who, String token) throws Exception {
        String body = json.writeValueAsString(Map.of("token", token, "platform", "ANDROID", "locale", "ru"));
        mvc.perform(put("/devices")
                        .header("Authorization", who.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    private UUID createSeries(Family f) throws Exception {
        String body = json.writeValueAsString(Map.of(
                "teenId", f.teen().id(),
                "title", "Walk dog",
                "rewardAmount", 100,
                "currency", "KZT",
                "recurrence", "DAILY",
                "dueTime", "23:59"));
        String response = fx.send("/task-series", body, f.parent().bearer())
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    private ResultActions deleteMe(Person who) throws Exception {
        return deleteMe(who.bearer());
    }

    private ResultActions deleteMe(String bearer) throws Exception {
        return mvc.perform(delete("/me").header("Authorization", bearer));
    }

    private String joinCodeOf(UUID parentId) {
        return jdbc.queryForObject("SELECT join_code FROM identity.users WHERE id = ?", String.class, parentId);
    }

    private JsonNode signIn(String sub, String role, String name) throws Exception {
        String body =
                json.writeValueAsString(Map.of("idToken", tokenFor(sub, name), "role", role, "displayName", name));
        String response = mvc.perform(post("/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response);
    }

    private static String bearerOf(JsonNode signInResponse) {
        return "Bearer " + signInResponse.get("token").asText();
    }

    private String taskState(UUID id) {
        return jdbc.queryForObject("SELECT state FROM task.tasks WHERE id = ?", String.class, id);
    }

    private boolean seriesActive(UUID id) {
        return jdbc.queryForObject("SELECT active FROM task.task_series WHERE id = ?", Boolean.class, id);
    }

    private int activeLinks(String column, UUID userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM identity.family_links WHERE " + column + " = ? AND removed_at IS NULL",
                Integer.class,
                userId);
    }
}
