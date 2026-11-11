package kz.tiyn.identity;

import static kz.tiyn.support.FakeGoogleConfig.tokenFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@AutoConfigureMockMvc
class JoinApprovalWithAccountEditsTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void regeneratingTheCodeKeepsApprovalOnAndPendingRequestsDecidable() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode earlyTeen = signIn("TEEN", "Dana");
        String oldCode = parent.at("/user/joinCode").asText();
        String pending = requestId(join(earlyTeen, oldCode).andExpect(status().isAccepted()));

        JsonNode regenerated = body(regenerate(parent).andExpect(status().isOk()));

        String newCode = regenerated.get("joinCode").asText();
        assertThat(newCode).isNotEqualTo(oldCode);
        assertThat(regenerated.get("joinApproval").asBoolean()).isTrue();
        me(parent).andExpect(jsonPath("$.joinApproval").value(true));
        assertThat(storedApproval(parent)).isTrue();

        mvc.perform(get("/links/requests").header("Authorization", bearer(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(pending));

        JsonNode lateTeen = signIn("TEEN", "Bota");
        join(lateTeen, oldCode)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_join_code"));
        join(lateTeen, newCode).andExpect(status().isAccepted());

        decide(parent, pending, "approve").andExpect(status().isOk());
        assertThat(linkRows(parent, earlyTeen)).isEqualTo(1);
        assertThat(linkRows(parent, lateTeen)).isZero();
    }

    @Test
    void aPendingRequestForTheOldCodeCanBeDeclinedAfterRegenerating() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode teen = signIn("TEEN", "Dana");
        String pending = requestId(join(teen, parent.at("/user/joinCode").asText()));

        regenerate(parent).andExpect(status().isOk());

        decide(parent, pending, "decline").andExpect(status().isOk());
        assertThat(linkRows(parent, teen)).isZero();
    }

    @Test
    void theJoinRateLimiterStillGuardsTheApprovalPathAfterRegenerating() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        String newCode = body(regenerate(parent).andExpect(status().isOk()))
                .get("joinCode")
                .asText();
        JsonNode guesser = signIn("TEEN", "Mallory");

        for (int i = 0; i < 5; i++) {
            join(guesser, "ZZZZZZ").andExpect(status().isUnprocessableEntity());
        }

        join(guesser, newCode)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("join_rate_limited"));
        assertThat(requestCount(parent)).isZero();
    }

    @Test
    void theRequestListShowsTheNormalisedStoredNameOfTheTeen() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode teen = signIn("TEEN", "Dana");
        mvc.perform(patch("/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", bearer(teen))
                        .content("{\"displayName\":\"  \\u202EDa\\u0000na\\u200B   Aibek\\t\\u2069 \"}"))
                .andExpect(status().isOk());

        join(teen, parent.at("/user/joinCode").asText()).andExpect(status().isAccepted());

        mvc.perform(get("/links/requests").header("Authorization", bearer(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].teenDisplayName").value("Dana Aibek"));
    }

    @Test
    void renamingAfterTheRequestIsShownUnderTheNewName() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode teen = signIn("TEEN", "Dana");
        join(teen, parent.at("/user/joinCode").asText()).andExpect(status().isAccepted());

        mvc.perform(patch("/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", bearer(teen))
                        .content("{\"displayName\":\"Danara\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/links/requests").header("Authorization", bearer(parent)))
                .andExpect(jsonPath("$.items[0].teenDisplayName").value("Danara"));
    }

    private JsonNode approvingParent(String name) throws Exception {
        JsonNode parent = signIn("PARENT", name);
        mvc.perform(put("/me/join-approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", bearer(parent))
                        .content("{\"required\":true}"))
                .andExpect(status().isOk());
        return parent;
    }

    private ResultActions regenerate(JsonNode parent) throws Exception {
        return mvc.perform(post("/me/join-code/regenerate").header("Authorization", bearer(parent)));
    }

    private ResultActions me(JsonNode who) throws Exception {
        return mvc.perform(get("/me").header("Authorization", bearer(who)));
    }

    private ResultActions join(JsonNode teen, String code) throws Exception {
        return mvc.perform(post("/links/join")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", bearer(teen))
                .content("{\"code\":\"" + code + "\"}"));
    }

    private ResultActions decide(JsonNode who, String requestId, String action) throws Exception {
        return mvc.perform(post("/links/requests/" + requestId + "/" + action).header("Authorization", bearer(who)));
    }

    private String requestId(ResultActions result) throws Exception {
        return body(result).get("requestId").asText();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private boolean storedApproval(JsonNode parent) {
        return jdbc.queryForObject(
                "SELECT join_approval_required FROM identity.users WHERE id = ?", Boolean.class, id(parent));
    }

    private int requestCount(JsonNode parent) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM identity.join_requests WHERE parent_id = ?", Integer.class, id(parent));
    }

    private int linkRows(JsonNode parent, JsonNode teen) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM identity.family_links WHERE parent_id = ? AND teen_id = ?",
                Integer.class,
                id(parent),
                id(teen));
    }

    private JsonNode signIn(String role, String name) throws Exception {
        String request = json.writeValueAsString(json.createObjectNode()
                .put("idToken", tokenFor("sub-" + UUID.randomUUID(), name))
                .put("role", role)
                .put("displayName", name));
        return body(mvc.perform(post("/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk()));
    }

    private static UUID id(JsonNode signInResponse) {
        return UUID.fromString(signInResponse.at("/user/id").asText());
    }

    private static String bearer(JsonNode signInResponse) {
        return "Bearer " + signInResponse.get("token").asText();
    }
}
