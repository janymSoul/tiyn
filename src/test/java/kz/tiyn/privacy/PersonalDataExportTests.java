package kz.tiyn.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import kz.tiyn.ledger.BalanceReader;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.support.TestImages;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "tiyn.export.page-size=2")
class PersonalDataExportTests {

    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "refreshToken",
            "token",
            "idpSub",
            "email",
            "idempotencyKey",
            "objectKey",
            "deviceToken",
            "attempts",
            "windowStartedAt",
            "password",
            "secret");

    private record Export(String raw, JsonNode root, MockHttpServletResponse response) {}

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    BalanceReader balances;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    private Export export(String bearer) throws Exception {
        MvcResult result = mvc.perform(get("/me/export").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn();
        String raw = result.getResponse().getContentAsString();
        return new Export(raw, json.readTree(raw), result.getResponse());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        node.forEach(child -> names.addAll(fieldNames(child)));
        return names;
    }

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private void assertNoSecrets(Export export) {
        assertThat(fieldNames(export.root())).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        assertThat(export.raw()).doesNotContain("Bearer ").doesNotContain("sub-");
    }

    private void submitText(Family f, UUID taskId) throws Exception {
        fx.send(
                        "/tasks/" + taskId + "/submit",
                        "{\"text\":\"done\"}",
                        f.teen().bearer(),
                        "Idempotency-Key",
                        "s-" + taskId)
                .andExpect(status().isOk());
    }

    private void submitImage(Family f, UUID taskId) throws Exception {
        mvc.perform(multipart("/tasks/" + taskId + "/submit")
                        .file(new MockMultipartFile("file", "p.png", "image/png", TestImages.png(8, 8)))
                        .header("Authorization", f.teen().bearer())
                        .header("Idempotency-Key", "img-" + taskId))
                .andExpect(status().isOk());
    }

    private void approve(Family f, UUID taskId) throws Exception {
        submitText(f, taskId);
        fx.send("/tasks/" + taskId + "/approve", "", f.parent().bearer()).andExpect(status().isOk());
    }

    private Family familyWithActivity() throws Exception {
        Family f = fx.linkedFamily();
        UUID approved = fx.createTask(f, 100_000);
        approve(f, approved);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(balances.balance(f.teen().id()).balanceAmount())
                        .isEqualTo(100_000));
        submitImage(f, fx.createTask(f, 5_000));
        String goal = json.readTree(fx.send(
                                "/goals",
                                "{\"name\":\"Headphones\",\"targetAmount\":900000}",
                                f.teen().bearer())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();
        fx.send("/goals/" + goal + "/lock", "{\"amount\":40000}", f.teen().bearer(), "Idempotency-Key", "lock-1")
                .andExpect(status().isOk());
        fx.send(
                        "/payouts",
                        "{\"parentId\":\"" + f.parent().id() + "\",\"amount\":30000}",
                        f.teen().bearer(),
                        "Idempotency-Key",
                        "payout-1")
                .andExpect(status().isCreated());
        return f;
    }

    @Test
    void aTeenExportHoldsEverySectionOfTheirOwnData() throws Exception {
        Family f = familyWithActivity();

        Export export = export(f.teen().bearer());
        JsonNode root = export.root();

        assertThat(root.get("generatedAt").asText()).isNotBlank();
        assertThat(root.get("consistency").asText()).contains("not one global snapshot");
        assertThat(root.at("/profile/id").asText()).isEqualTo(f.teen().id().toString());
        assertThat(root.at("/profile/role").asText()).isEqualTo("TEEN");
        assertThat(root.at("/profile/displayName").asText()).isEqualTo("Dana");
        assertThat(root.at("/profile/createdAt").asText()).isNotBlank();
        assertThat(root.at("/profile/joinCode").isMissingNode()).isTrue();
        assertThat(root.get("familyLinks")).hasSize(1);
        assertThat(root.at("/familyLinks/0/id").asText())
                .isEqualTo(f.parent().id().toString());
        assertThat(root.at("/familyLinks/0/displayName").asText()).isEqualTo("Aigul");
        assertThat(root.at("/familyLinks/0/linkedAt").asText()).isNotBlank();
        assertThat(root.at("/wallet/balanceAmount").asLong()).isEqualTo(100_000);
        assertThat(root.at("/wallet/lockedAmount").asLong()).isEqualTo(40_000);
        assertThat(root.at("/wallet/currency").asText()).isEqualTo("KZT");
        assertThat(root.get("savingsGoals")).hasSize(1);
        assertThat(root.at("/savingsGoals/0/savedAmount").asLong()).isEqualTo(40_000);
        assertThat(root.get("payouts")).hasSize(1);
        assertThat(root.at("/payouts/0/amount").asLong()).isEqualTo(30_000);
        assertThat(root.at("/payouts/0/state").asText()).isEqualTo("REQUESTED");
        assertThat(root.at("/payouts/0/parentId").asText())
                .isEqualTo(f.parent().id().toString());
        assertNoSecrets(export);
    }

    @Test
    void theTeenTasksCarryTheirProofsAndTheLedgerListsEveryMovement() throws Exception {
        Family f = familyWithActivity();

        JsonNode root = export(f.teen().bearer()).root();

        assertThat(root.get("tasks")).hasSize(2);
        JsonNode withImage = taskWhere(root, "SUBMITTED");
        JsonNode approved = taskWhere(root, "APPROVED");
        assertThat(approved.at("/proofs/0/text").asText()).isEqualTo("done");
        assertThat(approved.at("/proofs/0/hasImage").asBoolean()).isFalse();
        assertThat(approved.at("/proofs/0/imageUrl").isMissingNode()).isTrue();
        assertThat(approved.get("decidedBy").asText()).isEqualTo(f.parent().id().toString());
        assertThat(withImage.at("/proofs/0/hasImage").asBoolean()).isTrue();
        assertThat(withImage.at("/proofs/0/imageUrl").asText()).isNotBlank();
        assertThat(withImage.at("/proofs/0/attemptNo").asInt()).isEqualTo(1);
        assertThat(withImage.at("/proofs/0/submittedAt").asText()).isNotBlank();
        assertThat(withImage.get("rewardAmount").asLong()).isEqualTo(5_000);
        assertThat(withImage.get("deadlineAt").asText()).isNotBlank();
        assertThat(root.get("ledgerEntries")).hasSize(2);
        assertThat(root.at("/ledgerEntries/0/kind").asText()).isEqualTo("GOAL_LOCK");
        assertThat(root.at("/ledgerEntries/0/lockedDelta").asLong()).isEqualTo(40_000);
        assertThat(root.at("/ledgerEntries/1/kind").asText()).isEqualTo("TASK_REWARD");
        assertThat(root.at("/ledgerEntries/1/balanceDelta").asLong()).isEqualTo(100_000);
    }

    private static JsonNode taskWhere(JsonNode root, String state) {
        for (JsonNode task : root.get("tasks")) {
            if (state.equals(task.get("state").asText())) {
                return task;
            }
        }
        throw new AssertionError("no task in state " + state);
    }

    @Test
    void aParentExportHoldsWhatTheyCreatedAndNotTheTeensWalletOrProofs() throws Exception {
        Family f = familyWithActivity();
        fx.send(
                        "/task-series",
                        "{\"teenId\":\"" + f.teen().id() + "\",\"title\":\"Dishes\",\"rewardAmount\":50000,"
                                + "\"currency\":\"KZT\",\"recurrence\":\"DAILY\",\"dueTime\":\"18:00:00\"}",
                        f.parent().bearer())
                .andExpect(status().isCreated());
        String joinCode = jdbc.queryForObject(
                "SELECT join_code FROM identity.users WHERE id = ?",
                String.class,
                f.parent().id());

        Export export = export(f.parent().bearer());
        JsonNode root = export.root();

        assertThat(root.at("/profile/role").asText()).isEqualTo("PARENT");
        assertThat(root.at("/profile/joinCode").asText()).isEqualTo(joinCode);
        assertThat(root.at("/familyLinks/0/id").asText())
                .isEqualTo(f.teen().id().toString());
        assertThat(root.at("/familyLinks/0/displayName").asText()).isEqualTo("Dana");
        assertThat(root.get("tasks").size()).isGreaterThanOrEqualTo(2);
        assertThat(root.get("tasks").get(0).has("proofs")).isFalse();
        assertThat(root.get("taskSeries")).hasSize(1);
        assertThat(root.at("/taskSeries/0/recurrence").asText()).isEqualTo("DAILY");
        assertThat(root.get("payouts")).hasSize(1);
        assertThat(root.at("/payouts/0/teenId").asText())
                .isEqualTo(f.teen().id().toString());
        assertThat(root.has("wallet")).isFalse();
        assertThat(root.has("ledgerEntries")).isFalse();
        assertThat(root.has("savingsGoals")).isFalse();
        assertNoSecrets(export);
    }

    @Test
    void aTeenWithNothingYetGetsEmptySectionsAndAZeroWallet() throws Exception {
        var teen = fx.unlinkedTeen();

        Export export = export(teen.bearer());
        JsonNode root = export.root();

        assertThat(root.at("/profile/id").asText()).isEqualTo(teen.id().toString());
        for (String section : List.of("familyLinks", "tasks", "ledgerEntries", "savingsGoals", "payouts")) {
            assertThat(root.get(section).isArray()).as(section).isTrue();
            assertThat(root.get(section)).as(section).isEmpty();
        }
        assertThat(root.at("/wallet/balanceAmount").asLong()).isZero();
        assertThat(root.at("/wallet/lockedAmount").asLong()).isZero();
        assertNoSecrets(export);
    }

    @Test
    void aParentWithNothingYetGetsEmptySections() throws Exception {
        var parent = fx.unjoinedFamily().family().parent();

        JsonNode root = export(parent.bearer()).root();

        for (String section : List.of("familyLinks", "tasks", "taskSeries", "payouts")) {
            assertThat(root.get(section)).as(section).isEmpty();
        }
    }

    @Test
    void nothingOfAnotherFamilyEverAppears() throws Exception {
        Family mine = familyWithActivity();
        Family other = familyWithActivity();
        String otherTask = jdbc.queryForObject(
                "SELECT id::text FROM task.tasks WHERE teen_id = ? LIMIT 1",
                String.class,
                other.teen().id());
        String otherGoal = jdbc.queryForObject(
                "SELECT id::text FROM ledger.savings_goals WHERE teen_id = ?",
                String.class,
                other.teen().id());
        List<String> foreign =
                List.of(other.teen().id().toString(), other.parent().id().toString(), otherTask, otherGoal);

        for (String bearer : List.of(mine.teen().bearer(), mine.parent().bearer())) {
            assertThat(export(bearer).raw()).doesNotContain(foreign);
        }
    }

    @Test
    void largeCollectionsStreamCompleteAndInTheDocumentedOrder() throws Exception {
        Family f = fx.linkedFamily();
        UUID teen = f.teen().id();
        jdbc.update("INSERT INTO ledger.wallets (teen_id, currency) VALUES (?, 'KZT') ON CONFLICT DO NOTHING", teen);
        Instant tied = Instant.parse("2026-10-01T10:00:00Z");
        for (int i = 0; i < 7; i++) {
            insertEntry(teen, i < 3 ? tied : tied.plusSeconds(i));
            insertCancelledPayout(teen, f.parent().id(), tied.plusSeconds(i % 4));
            insertGoal(teen, tied.plusSeconds(i % 3));
        }
        for (int i = 0; i < 5; i++) {
            fx.createTask(f, 1_000 + i);
        }

        JsonNode asTeen = export(f.teen().bearer()).root();
        JsonNode asParent = export(f.parent().bearer()).root();

        assertThat(ids(asTeen.get("ledgerEntries")))
                .containsExactlyElementsOf(orderedIds(
                        "SELECT id::text FROM ledger.ledger_entries WHERE teen_id = ? ORDER BY created_at DESC, id DESC",
                        teen));
        assertThat(ids(asTeen.get("savingsGoals")))
                .containsExactlyElementsOf(orderedIds(
                        "SELECT id::text FROM ledger.savings_goals WHERE teen_id = ? ORDER BY created_at DESC, id DESC",
                        teen));
        assertThat(ids(asTeen.get("payouts")))
                .containsExactlyElementsOf(orderedIds(
                        "SELECT id::text FROM ledger.payouts WHERE teen_id = ? ORDER BY requested_at DESC, id DESC",
                        teen));
        assertThat(ids(asTeen.get("tasks")))
                .containsExactlyElementsOf(orderedIds(
                        "SELECT id::text FROM task.tasks WHERE teen_id = ? ORDER BY created_at DESC, id DESC", teen));
        assertThat(ids(asParent.get("tasks")))
                .containsExactlyElementsOf(orderedIds(
                        "SELECT id::text FROM task.tasks WHERE parent_id = ? ORDER BY created_at DESC, id DESC",
                        f.parent().id()));
        assertThat(ids(asParent.get("payouts")))
                .containsExactlyElementsOf(orderedIds(
                        "SELECT id::text FROM ledger.payouts WHERE parent_id = ? ORDER BY requested_at DESC, id DESC",
                        f.parent().id()));
        assertThat(new TreeSet<>(ids(asTeen.get("ledgerEntries")))).hasSize(7);
    }

    private List<String> orderedIds(String sql, UUID owner) {
        return jdbc.queryForList(sql, String.class, owner);
    }

    private void insertEntry(UUID teen, Instant at) {
        jdbc.update(
                """
                INSERT INTO ledger.ledger_entries
                    (id, teen_id, currency, kind, balance_delta, ref_id, idempotency_key, created_at)
                VALUES (?, ?, 'KZT', 'TASK_REWARD', 100, ?, ?, ?)
                """,
                UUID.randomUUID(),
                teen,
                UUID.randomUUID(),
                "bulk-" + UUID.randomUUID(),
                Timestamp.from(at));
    }

    private void insertGoal(UUID teen, Instant at) {
        jdbc.update(
                """
                INSERT INTO ledger.savings_goals (id, teen_id, currency, name, target_amount, created_at)
                VALUES (?, ?, 'KZT', 'Bike', 1000000, ?)
                """,
                UUID.randomUUID(),
                teen,
                Timestamp.from(at));
    }

    private void insertCancelledPayout(UUID teen, UUID parent, Instant at) {
        jdbc.update(
                """
                INSERT INTO ledger.payouts
                    (id, teen_id, currency, parent_id, amount, state, idempotency_key,
                     requested_at, resolved_at, cancel_reason)
                VALUES (?, ?, 'KZT', ?, 10000, 'CANCELLED', ?, ?, ?, 'TEEN_CANCELLED')
                """,
                UUID.randomUUID(),
                teen,
                parent,
                "bulk-" + UUID.randomUUID(),
                Timestamp.from(at),
                Timestamp.from(at.plusSeconds(1)));
    }

    @Test
    void theResponseIsADatedAttachmentThatMustNotBeCached() throws Exception {
        Family f = fx.linkedFamily();

        Export export = export(f.teen().bearer());
        String date = Instant.parse(export.root().get("generatedAt").asText())
                .atOffset(ZoneOffset.UTC)
                .toLocalDate()
                .toString();

        assertThat(export.response().getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"tiyn-export-" + date + ".json\"");
        assertThat(export.response().getHeader("Cache-Control")).contains("no-store");
        assertThat(export.response().getContentType()).startsWith("application/json");
    }

    @Test
    void aSecondExportInsideTheWindowIsRefusedButOtherUsersAreNot() throws Exception {
        Family f = fx.linkedFamily();
        export(f.teen().bearer());

        mvc.perform(get("/me/export").header("Authorization", f.teen().bearer()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("export_rate_limited"));

        Export parentExport = export(f.parent().bearer());
        assertThat(parentExport.root().at("/profile/role").asText())
                .as(parentExport.raw())
                .isEqualTo("PARENT");
    }

    @Test
    void anonymousCallersAreRejected() throws Exception {
        mvc.perform(get("/me/export"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthenticated"));
    }
}
