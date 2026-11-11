package kz.tiyn.identity;

import static kz.tiyn.support.FakeGoogleConfig.tokenFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@AutoConfigureMockMvc
class JoinApprovalWithRefreshTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtDecoder decoder;

    @Test
    void parentSignInCarriesBothTheRefreshTokenAndTheJoinApprovalFlag() throws Exception {
        JsonNode parent = signIn("PARENT", "Aigul");

        assertThat(parent.get("refreshToken").asText()).isNotBlank();
        assertThat(parent.at("/user/joinCode").asText()).hasSize(6);
        assertThat(parent.at("/user/joinApproval").isBoolean()).isTrue();
        assertThat(parent.at("/user/joinApproval").asBoolean()).isFalse();
    }

    @Test
    void teenSignInHasTheRefreshTokenAndNoParentOnlyFields() throws Exception {
        JsonNode teen = signIn("TEEN", "Dana");

        assertThat(teen.get("refreshToken").asText()).isNotBlank();
        assertThat(teen.at("/user/joinApproval").isMissingNode()).isTrue();
        assertThat(teen.at("/user/joinCode").isMissingNode()).isTrue();
    }

    @Test
    void aRefreshedParentTokenDrivesTheWholeJoinApprovalFlow() throws Exception {
        JsonNode parent = signIn("PARENT", "Aigul");
        JsonNode teen = signIn("TEEN", "Dana");
        JsonNode parentSession = refreshOk(parent.get("refreshToken").asText());
        JsonNode teenSession = refreshOk(teen.get("refreshToken").asText());
        String parentBearer = bearer(parentSession);
        String teenBearer = bearer(teenSession);

        mvc.perform(put("/me/join-approval")
                        .header("Authorization", parentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"required\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.joinApproval").value(true));

        String requestId = json.readTree(mvc.perform(post("/links/join")
                                .header("Authorization", teenBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\":\""
                                        + parent.at("/user/joinCode").asText() + "\"}"))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("requestId")
                .asText();

        mvc.perform(get("/links/requests").header("Authorization", parentBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(requestId));
        mvc.perform(post("/links/requests/" + requestId + "/approve").header("Authorization", parentBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        JsonNode again = refreshOk(parentSession.get("refreshToken").asText());
        assertThat(again.at("/user/joinApproval").asBoolean()).isTrue();
        mvc.perform(get("/family").header("Authorization", bearer(again)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(1));
    }

    @Test
    void aRefreshedTeenTokenCanWithdrawItsPendingRequest() throws Exception {
        JsonNode parent = signIn("PARENT", "Aigul");
        JsonNode teen = signIn("TEEN", "Dana");
        mvc.perform(put("/me/join-approval")
                        .header("Authorization", bearer(parent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"required\":true}"))
                .andExpect(status().isOk());
        String teenBearer = bearer(refreshOk(teen.get("refreshToken").asText()));
        String requestId = json.readTree(mvc.perform(post("/links/join")
                                .header("Authorization", teenBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\":\""
                                        + parent.at("/user/joinCode").asText() + "\"}"))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("requestId")
                .asText();

        mvc.perform(delete("/links/requests/" + requestId).header("Authorization", teenBearer))
                .andExpect(status().isNoContent());
    }

    @Test
    void theRoleReReadOnRefreshIsInTheNewAccessTokenAndWorksOnParentEndpoints() throws Exception {
        JsonNode session = signIn("TEEN", "Dana");
        UUID userId = UUID.fromString(session.at("/user/id").asText());
        jdbc.update("UPDATE identity.users SET role = 'PARENT' WHERE id = ?", userId);

        JsonNode refreshed = refreshOk(session.get("refreshToken").asText());

        assertThat(decoder.decode(refreshed.get("token").asText()).getClaimAsString("role"))
                .isEqualTo("PARENT");
        assertThat(refreshed.at("/user/joinApproval").asBoolean()).isFalse();
        mvc.perform(get("/links/requests").header("Authorization", bearer(refreshed)))
                .andExpect(status().isOk());
        mvc.perform(put("/me/join-approval")
                        .header("Authorization", bearer(refreshed))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"required\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.joinApproval").value(true));
    }

    private JsonNode refreshOk(String refreshToken) throws Exception {
        String response = mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(json.createObjectNode().put("refreshToken", refreshToken))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response);
    }

    private JsonNode signIn(String role, String name) throws Exception {
        String sub = "sub-" + UUID.randomUUID();
        ResultActions result = mvc.perform(post("/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(json.createObjectNode()
                        .put("idToken", tokenFor(sub, name))
                        .put("role", role))));
        return json.readTree(
                result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static String bearer(JsonNode session) {
        return "Bearer " + session.get("token").asText();
    }
}
