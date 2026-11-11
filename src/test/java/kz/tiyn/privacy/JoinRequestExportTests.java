package kz.tiyn.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import kz.tiyn.task.TaskFixtures.Person;
import kz.tiyn.task.TaskFixtures.Unjoined;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "tiyn.export.page-size=2")
class JoinRequestExportTests {

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

    private JsonNode export(Person who) throws Exception {
        return json.readTree(rawExport(who));
    }

    private String rawExport(Person who) throws Exception {
        return mvc.perform(get("/me/export").header("Authorization", who.bearer()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void requireApproval(Person parent) throws Exception {
        mvc.perform(put("/me/join-approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", parent.bearer())
                        .content("{\"required\":true}"))
                .andExpect(status().isOk());
    }

    private String ask(Person teen, String code) throws Exception {
        String body = mvc.perform(post("/links/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", teen.bearer())
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body).get("requestId").asText();
    }

    private void decide(Person parent, String requestId, String action) throws Exception {
        mvc.perform(post("/links/requests/" + requestId + "/" + action).header("Authorization", parent.bearer()))
                .andExpect(status().isOk());
    }

    private List<String> orderedIds(String sql, UUID owner) {
        return jdbc.queryForList(sql, String.class, owner);
    }

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private static JsonNode byState(JsonNode requests, String state) {
        for (JsonNode request : requests) {
            if (state.equals(request.get("state").asText())) {
                return request;
            }
        }
        throw new AssertionError("no join request in state " + state);
    }

    @Test
    void aTeenExportListsTheirRequestsWithTheParentIdAndNameOnly() throws Exception {
        Unjoined first = fx.unjoinedFamily();
        Unjoined second = fx.unjoinedFamily();
        Unjoined third = fx.unjoinedFamily();
        Person teen = first.family().teen();
        for (Unjoined parentFamily : List.of(first, second, third)) {
            requireApproval(parentFamily.family().parent());
        }
        ask(teen, first.joinCode());
        String approved = ask(teen, second.joinCode());
        String declined = ask(teen, third.joinCode());
        decide(second.family().parent(), approved, "approve");
        decide(third.family().parent(), declined, "decline");

        JsonNode root = export(teen);

        JsonNode requests = root.get("joinRequests");
        assertThat(requests).hasSize(3);
        assertThat(ids(requests))
                .containsExactlyElementsOf(orderedIds(
                        "SELECT id::text FROM identity.join_requests WHERE teen_id = ? ORDER BY created_at DESC, id DESC",
                        teen.id()));
        JsonNode pending = byState(requests, "PENDING");
        assertThat(pending.get("parentId").asText())
                .isEqualTo(first.family().parent().id().toString());
        assertThat(pending.get("parentDisplayName").asText()).isEqualTo("Aigul");
        assertThat(pending.get("createdAt").asText()).isNotBlank();
        assertThat(pending.has("decidedAt")).isFalse();
        assertThat(pending.has("teenId")).isFalse();
        assertThat(pending.has("teenDisplayName")).isFalse();
        assertThat(byState(requests, "APPROVED").get("decidedAt").asText()).isNotBlank();
        assertThat(byState(requests, "DECLINED").get("decidedAt").asText()).isNotBlank();
        requests.forEach(request -> assertThat(request.fieldNames())
                .toIterable()
                .isSubsetOf("id", "parentId", "parentDisplayName", "state", "createdAt", "decidedAt"));
    }

    @Test
    void aParentExportListsTheRequestsAddressedToThemAndTheApprovalSetting() throws Exception {
        Unjoined mine = fx.unjoinedFamily();
        Unjoined otherTeen1 = fx.unjoinedFamily();
        Unjoined otherTeen2 = fx.unjoinedFamily();
        Unjoined unrelated = fx.unjoinedFamily();
        Person parent = mine.family().parent();
        requireApproval(parent);
        requireApproval(unrelated.family().parent());
        ask(mine.family().teen(), mine.joinCode());
        String approved = ask(otherTeen1.family().teen(), mine.joinCode());
        String declined = ask(otherTeen2.family().teen(), mine.joinCode());
        ask(unrelated.family().teen(), unrelated.joinCode());
        decide(parent, approved, "approve");
        decide(parent, declined, "decline");

        String raw = rawExport(parent);
        JsonNode root = json.readTree(raw);

        assertThat(root.at("/profile/joinApproval").isBoolean()).isTrue();
        assertThat(root.at("/profile/joinApproval").asBoolean()).isTrue();
        JsonNode requests = root.get("joinRequests");
        assertThat(requests).hasSize(3);
        assertThat(ids(requests))
                .containsExactlyElementsOf(orderedIds(
                        "SELECT id::text FROM identity.join_requests WHERE parent_id = ? ORDER BY created_at DESC, id DESC",
                        parent.id()));
        JsonNode pending = byState(requests, "PENDING");
        assertThat(pending.get("teenId").asText())
                .isEqualTo(mine.family().teen().id().toString());
        assertThat(pending.get("teenDisplayName").asText()).isEqualTo("Dana");
        assertThat(pending.has("parentId")).isFalse();
        assertThat(byState(requests, "APPROVED").get("decidedAt").asText()).isNotBlank();
        assertThat(byState(requests, "DECLINED").get("decidedAt").asText()).isNotBlank();
        assertThat(raw)
                .doesNotContain(unrelated.family().teen().id().toString())
                .doesNotContain(unrelated.family().parent().id().toString());
    }

    @Test
    void theApprovalSettingIsShownToParentsOnlyAndReadsFalseByDefault() throws Exception {
        Family f = fx.linkedFamily();

        JsonNode parent = export(f.parent());
        JsonNode teen = export(f.teen());

        assertThat(parent.at("/profile/joinApproval").isBoolean()).isTrue();
        assertThat(parent.at("/profile/joinApproval").asBoolean()).isFalse();
        assertThat(teen.at("/profile/joinApproval").isMissingNode()).isTrue();
        assertThat(teen.get("joinRequests")).isEmpty();
        assertThat(parent.get("joinRequests")).isEmpty();
    }

    @Test
    void refreshTokensAndJoinAttemptsAreNeverExported() throws Exception {
        Unjoined u = fx.unjoinedFamily();
        Person parent = u.family().parent();
        Person teen = u.family().teen();
        requireApproval(parent);
        ask(teen, u.joinCode());
        mvc.perform(post("/links/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", teen.bearer())
                        .content("{\"code\":\"ZZZZZZ\"}"))
                .andExpect(status().isUnprocessableEntity());
        for (Person who : List.of(parent, teen)) {
            List<String> hashes = jdbc.queryForList(
                    "SELECT token_hash FROM identity.refresh_tokens WHERE user_id = ?", String.class, who.id());
            List<String> families = jdbc.queryForList(
                    "SELECT family_id::text FROM identity.refresh_tokens WHERE user_id = ?", String.class, who.id());
            assertThat(hashes).isNotEmpty();
            assertThat(families).isNotEmpty();

            String raw = rawExport(who);

            assertThat(raw).doesNotContain(hashes).doesNotContain(families);
            assertThat(raw.toLowerCase()).doesNotContain("refresh").doesNotContain("attempt");
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM identity.join_attempts WHERE teen_id = ?", Long.class, teen.id()))
                .isPositive();
    }
}
