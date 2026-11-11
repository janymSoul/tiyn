package kz.tiyn.identity;

import static kz.tiyn.support.FakeGoogleConfig.tokenFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class AccountDeletionAcrossFeaturesTests {

    private static final String PLACEHOLDER = "Deleted user";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void deletingAnAccountMakesEveryRefreshTokenOfItUnusableAndLogoutStaysNoContent() throws Exception {
        String sub = "sub-" + UUID.randomUUID();
        JsonNode phone = signIn(sub, "PARENT", "Aigul");
        JsonNode tablet = signIn(sub, "PARENT", "Aigul");
        JsonNode bystander = signIn("sub-" + UUID.randomUUID(), "PARENT", "Bota");
        UUID parentId = userId(phone);
        assertThat(refreshTokensOf(parentId)).isEqualTo(2);

        deleteMe(bearer(phone)).andExpect(status().isNoContent());

        assertThat(refreshTokensOf(parentId)).isZero();
        for (JsonNode session : new JsonNode[] {phone, tablet}) {
            refresh(session)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("invalid_refresh_token"));
            logout(session).andExpect(status().isNoContent());
        }
        refresh(bystander).andExpect(status().isOk());
    }

    @Test
    void aRefreshTokenThatSurvivedForADeletedUserIsStillRefusedWithTheGenericError() throws Exception {
        JsonNode session = signIn("sub-" + UUID.randomUUID(), "TEEN", "Dana");
        UUID teenId = userId(session);
        jdbc.update("UPDATE identity.users SET deleted_at = now() WHERE id = ?", teenId);

        refresh(session)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_refresh_token"));
        logout(session).andExpect(status().isNoContent());
    }

    @Test
    void deletingATeenWithdrawsTheirPendingRequestsKeepsDecidedOnesAndClearsTheirJoinAttempts() throws Exception {
        JsonNode parentA = signIn("sub-" + UUID.randomUUID(), "PARENT", "Aigul");
        JsonNode parentB = signIn("sub-" + UUID.randomUUID(), "PARENT", "Bota");
        JsonNode teen = signIn("sub-" + UUID.randomUUID(), "TEEN", "Dana");
        requireApproval(parentA);
        requireApproval(parentB);
        UUID pending = askToJoin(teen, parentA);
        UUID declined = askToJoin(teen, parentB);
        mvc.perform(post("/links/requests/" + declined + "/decline").header("Authorization", bearer(parentB)))
                .andExpect(status().isOk());
        join(teen, "ZZZZZZ").andExpect(status().isUnprocessableEntity());
        assertThat(joinAttemptRows(userId(teen))).isEqualTo(1);

        deleteMe(bearer(teen)).andExpect(status().isNoContent());

        assertThat(requestState(pending)).isEqualTo("WITHDRAWN");
        assertThat(requestState(declined)).isEqualTo("DECLINED");
        assertThat(joinAttemptRows(userId(teen))).isZero();
        mvc.perform(get("/links/requests").header("Authorization", bearer(parentA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(post("/links/requests/" + pending + "/approve").header("Authorization", bearer(parentA)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("invalid_transition"));
    }

    @Test
    void deletingAParentDeclinesTheRequestsAddressedToThemKeepsApprovedOnesAndKillsTheJoinCode() throws Exception {
        JsonNode parent = signIn("sub-" + UUID.randomUUID(), "PARENT", "Aigul");
        JsonNode approvedTeen = signIn("sub-" + UUID.randomUUID(), "TEEN", "Dana");
        JsonNode waitingTeen = signIn("sub-" + UUID.randomUUID(), "TEEN", "Eldar");
        JsonNode newcomer = signIn("sub-" + UUID.randomUUID(), "TEEN", "Farida");
        requireApproval(parent);
        UUID approved = askToJoin(approvedTeen, parent);
        mvc.perform(post("/links/requests/" + approved + "/approve").header("Authorization", bearer(parent)))
                .andExpect(status().isOk());
        UUID waiting = askToJoin(waitingTeen, parent);
        String oldCode = parent.at("/user/joinCode").asText();

        deleteMe(bearer(parent)).andExpect(status().isNoContent());

        assertThat(requestState(waiting)).isEqualTo("DECLINED");
        assertThat(requestState(approved)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject(
                        "SELECT join_approval_required FROM identity.users WHERE id = ?",
                        Boolean.class,
                        userId(parent)))
                .isFalse();
        join(newcomer, oldCode)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_join_code"));
        join(waitingTeen, oldCode)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_join_code"));
        mvc.perform(delete("/links/requests/" + waiting).header("Authorization", bearer(waitingTeen)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("invalid_transition"));
    }

    @Test
    void theUnexpiredAccessTokenOfADeletedAccountIsRefusedOnProfileRegenerateApprovalAndExport() throws Exception {
        JsonNode parent = signIn("sub-" + UUID.randomUUID(), "PARENT", "Aigul");
        String token = bearer(parent);
        deleteMe(token).andExpect(status().isNoContent());

        for (ResultActions call : new ResultActions[] {
            mvc.perform(patch("/me")
                    .header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"displayName\":\"Back\"}")),
            mvc.perform(post("/me/join-code/regenerate").header("Authorization", token)),
            mvc.perform(put("/me/join-approval")
                    .header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"required\":true}")),
            mvc.perform(get("/me/export").header("Authorization", token)),
            mvc.perform(get("/links/requests").header("Authorization", token))
        }) {
            call.andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthenticated"));
        }
        assertThat(jdbc.queryForObject(
                        "SELECT display_name FROM identity.users WHERE id = ?", String.class, userId(parent)))
                .isEqualTo(PLACEHOLDER);
    }

    @Test
    void theFormerCounterpartSeesThePlaceholderNameInTheirExportAndNothingInFamilyOrRequests() throws Exception {
        JsonNode parent = signIn("sub-" + UUID.randomUUID(), "PARENT", "Aigul");
        JsonNode linkedTeen = signIn("sub-" + UUID.randomUUID(), "TEEN", "Dana");
        JsonNode waitingTeen = signIn("sub-" + UUID.randomUUID(), "TEEN", "Eldar");
        join(linkedTeen, parent.at("/user/joinCode").asText()).andExpect(status().isOk());
        requireApproval(parent);
        askToJoin(waitingTeen, parent);

        deleteMe(bearer(parent)).andExpect(status().isNoContent());

        JsonNode linkedExport = export(linkedTeen);
        assertThat(linkedExport.at("/familyLinks/0/displayName").asText()).isEqualTo(PLACEHOLDER);
        assertThat(linkedExport.at("/familyLinks/0/removedAt").isMissingNode()).isFalse();
        JsonNode waitingExport = export(waitingTeen);
        assertThat(waitingExport.at("/joinRequests/0/parentDisplayName").asText())
                .isEqualTo(PLACEHOLDER);
        assertThat(waitingExport.at("/joinRequests/0/state").asText()).isEqualTo("DECLINED");
        mvc.perform(get("/family").header("Authorization", bearer(linkedTeen)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(0));
    }

    @Test
    void theFormerParentsExportShowsADeletedTeenUnderThePlaceholderName() throws Exception {
        JsonNode parent = signIn("sub-" + UUID.randomUUID(), "PARENT", "Aigul");
        JsonNode teen = signIn("sub-" + UUID.randomUUID(), "TEEN", "Dana");
        requireApproval(parent);
        askToJoin(teen, parent);

        deleteMe(bearer(teen)).andExpect(status().isNoContent());

        JsonNode export = export(parent);
        assertThat(export.at("/joinRequests/0/teenDisplayName").asText()).isEqualTo(PLACEHOLDER);
        assertThat(export.at("/joinRequests/0/state").asText()).isEqualTo("WITHDRAWN");
    }

    private JsonNode export(JsonNode session) throws Exception {
        String body = mvc.perform(get("/me/export").header("Authorization", bearer(session)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body);
    }

    private void requireApproval(JsonNode parent) throws Exception {
        mvc.perform(put("/me/join-approval")
                        .header("Authorization", bearer(parent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"required\":true}"))
                .andExpect(status().isOk());
    }

    private UUID askToJoin(JsonNode teen, JsonNode parent) throws Exception {
        String body = join(teen, parent.at("/user/joinCode").asText())
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(body).get("requestId").asText());
    }

    private ResultActions join(JsonNode teen, String code) throws Exception {
        return mvc.perform(post("/links/join")
                .header("Authorization", bearer(teen))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
    }

    private ResultActions refresh(JsonNode session) throws Exception {
        return mvc.perform(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(json.createObjectNode()
                        .put("refreshToken", session.get("refreshToken").asText()))));
    }

    private ResultActions logout(JsonNode session) throws Exception {
        return mvc.perform(post("/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(json.createObjectNode()
                        .put("refreshToken", session.get("refreshToken").asText()))));
    }

    private ResultActions deleteMe(String bearer) throws Exception {
        return mvc.perform(delete("/me").header("Authorization", bearer));
    }

    private JsonNode signIn(String sub, String role, String name) throws Exception {
        String body = json.writeValueAsString(json.createObjectNode()
                .put("idToken", tokenFor(sub, name))
                .put("role", role)
                .put("displayName", name));
        String response = mvc.perform(post("/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response);
    }

    private static String bearer(JsonNode session) {
        return "Bearer " + session.get("token").asText();
    }

    private static UUID userId(JsonNode session) {
        return UUID.fromString(session.at("/user/id").asText());
    }

    private int refreshTokensOf(UUID userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM identity.refresh_tokens WHERE user_id = ?", Integer.class, userId);
    }

    private int joinAttemptRows(UUID teenId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM identity.join_attempts WHERE teen_id = ?", Integer.class, teenId);
    }

    private String requestState(UUID requestId) {
        return jdbc.queryForObject("SELECT state FROM identity.join_requests WHERE id = ?", String.class, requestId);
    }
}
