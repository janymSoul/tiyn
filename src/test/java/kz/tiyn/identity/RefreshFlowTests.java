package kz.tiyn.identity;

import static kz.tiyn.support.FakeGoogleConfig.tokenFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
class RefreshFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtDecoder decoder;

    @Test
    void signInKeepsItsFieldsAndAddsAnOpaqueRefreshToken() throws Exception {
        JsonNode session = signIn("PARENT", "Aigul");

        assertThat(session.get("token").asText()).isNotBlank();
        assertThat(session.at("/user/role").asText()).isEqualTo("PARENT");
        assertThat(session.get("refreshToken").asText()).matches("[A-Za-z0-9_-]{43,}");
    }

    @Test
    void refreshRotatesTheTokenAndTheNewAccessTokenWorksOnMe() throws Exception {
        JsonNode session = signIn("PARENT", "Aigul");

        JsonNode refreshed = refreshOk(session.get("refreshToken").asText());

        assertThat(refreshed.get("refreshToken").asText())
                .isNotEqualTo(session.get("refreshToken").asText());
        assertThat(refreshed.at("/user/id").asText())
                .isEqualTo(session.at("/user/id").asText());
        assertThat(refreshed.at("/user/joinCode").asText())
                .isEqualTo(session.at("/user/joinCode").asText());
        mvc.perform(get("/me")
                        .header(
                                "Authorization",
                                "Bearer " + refreshed.get("token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(session.at("/user/id").asText()));
        refreshOk(refreshed.get("refreshToken").asText());
    }

    @Test
    void theNewAccessTokenCarriesTheRoleReadFromTheDatabaseNotFromTheOldToken() throws Exception {
        JsonNode session = signIn("TEEN", "Dana");
        UUID userId = UUID.fromString(session.at("/user/id").asText());
        jdbc.update("UPDATE identity.users SET role = 'PARENT' WHERE id = ?", userId);

        JsonNode refreshed = refreshOk(session.get("refreshToken").asText());

        assertThat(decoder.decode(refreshed.get("token").asText()).getClaimAsString("role"))
                .isEqualTo("PARENT");
        assertThat(refreshed.at("/user/role").asText()).isEqualTo("PARENT");
    }

    @Test
    void unknownMalformedBlankAndMissingTokensAllGetTheSameGeneric401() throws Exception {
        for (String body : new String[] {
            "{\"refreshToken\":\"" + UUID.randomUUID() + "\"}",
            "{\"refreshToken\":\"\"}",
            "{\"refreshToken\":\"   \"}",
            "{\"refreshToken\":null}",
            "{}"
        }) {
            refresh(body)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("invalid_refresh_token"));
        }
    }

    @Test
    void anExpiredTokenGetsTheSame401AsAnUnknownOne() throws Exception {
        String refreshToken = signIn("TEEN", "Dana").get("refreshToken").asText();
        jdbc.update(
                "UPDATE identity.refresh_tokens SET expires_at = now() - interval '1 second' WHERE token_hash = ?",
                sha256(refreshToken));

        refresh(refreshToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_refresh_token"));
    }

    @Test
    void reusingAnAlreadyRotatedTokenRevokesTheWholeFamilyIncludingTheNewestToken() throws Exception {
        String first = signIn("PARENT", "Aigul").get("refreshToken").asText();
        String second = refreshOk(first).get("refreshToken").asText();
        String third = refreshOk(second).get("refreshToken").asText();

        refresh(first)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_refresh_token"));

        for (String token : new String[] {first, second, third}) {
            refresh(token)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("invalid_refresh_token"));
        }
    }

    @Test
    void reuseInOneFamilyDoesNotTouchAnotherSessionOfTheSameUser() throws Exception {
        String sub = "sub-" + UUID.randomUUID();
        String phone = signInWith(sub, "TEEN", "Dana").get("refreshToken").asText();
        String tablet = signInWith(sub, null, null).get("refreshToken").asText();
        refreshOk(phone);

        refresh(phone).andExpect(status().isUnauthorized());

        refreshOk(tablet);
    }

    @Test
    void logoutRevokesTheWholeFamilyAndIsIdempotent() throws Exception {
        String first = signIn("TEEN", "Dana").get("refreshToken").asText();
        String newest = refreshOk(first).get("refreshToken").asText();

        logout(newest).andExpect(status().isNoContent());
        logout(newest).andExpect(status().isNoContent());

        for (String token : new String[] {first, newest}) {
            refresh(token).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void logoutWithAnOldRotatedTokenStillEndsTheSession() throws Exception {
        String first = signIn("TEEN", "Dana").get("refreshToken").asText();
        String newest = refreshOk(first).get("refreshToken").asText();

        logout(first).andExpect(status().isNoContent());

        refresh(newest).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutNeverRevealsWhetherTheTokenExisted() throws Exception {
        for (String body : new String[] {
            "{\"refreshToken\":\"" + UUID.randomUUID() + "\"}",
            "{\"refreshToken\":\"\"}",
            "{\"refreshToken\":null}",
            "{}"
        }) {
            mvc.perform(post("/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));
        }
    }

    @Test
    void logoutOfOneSessionLeavesAnotherSessionOfTheSameUserWorking() throws Exception {
        String sub = "sub-" + UUID.randomUUID();
        String phone = signInWith(sub, "TEEN", "Dana").get("refreshToken").asText();
        String tablet = signInWith(sub, null, null).get("refreshToken").asText();

        logout(phone).andExpect(status().isNoContent());

        refresh(phone).andExpect(status().isUnauthorized());
        refreshOk(tablet);
    }

    @Test
    void onlyTheHashOfTheRefreshTokenIsStored() throws Exception {
        String refreshToken = signIn("TEEN", "Dana").get("refreshToken").asText();

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM identity.refresh_tokens WHERE token_hash = ?",
                        Integer.class,
                        refreshToken))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM identity.refresh_tokens WHERE token_hash = ?",
                        Integer.class,
                        sha256(refreshToken)))
                .isEqualTo(1);
    }

    @Test
    void rotationKeepsTheFamilyAndMarksTheOldTokenUsed() throws Exception {
        String first = signIn("TEEN", "Dana").get("refreshToken").asText();
        String second = refreshOk(first).get("refreshToken").asText();

        UUID firstFamily = familyOf(first);
        assertThat(familyOf(second)).isEqualTo(firstFamily);
        assertThat(jdbc.queryForObject(
                        "SELECT used_at IS NOT NULL FROM identity.refresh_tokens WHERE token_hash = ?",
                        Boolean.class,
                        sha256(first)))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT used_at IS NULL AND revoked_at IS NULL FROM identity.refresh_tokens WHERE token_hash = ?",
                        Boolean.class,
                        sha256(second)))
                .isTrue();
    }

    @Test
    void refreshAndLogoutAreReachableWithoutAnAccessToken() throws Exception {
        mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_refresh_token"));
        mvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void anInvalidAuthorizationHeaderIsRejectedBeforeTheEndpointRuns() throws Exception {
        String refreshToken = signIn("TEEN", "Dana").get("refreshToken").asText();

        mvc.perform(post("/auth/refresh")
                        .header("Authorization", "Bearer expired-or-garbage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(json.createObjectNode().put("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthenticated"));
        refreshOk(refreshToken);
    }

    private UUID familyOf(String refreshToken) {
        return jdbc.queryForObject(
                "SELECT family_id FROM identity.refresh_tokens WHERE token_hash = ?", UUID.class, sha256(refreshToken));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode refreshOk(String refreshToken) throws Exception {
        String response = refresh(refreshToken)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response);
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return refreshBody(json.writeValueAsString(json.createObjectNode().put("refreshToken", refreshToken)));
    }

    private ResultActions refreshBody(String body) throws Exception {
        return mvc.perform(
                post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions logout(String refreshToken) throws Exception {
        return mvc.perform(post("/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(json.createObjectNode().put("refreshToken", refreshToken))));
    }

    private JsonNode signIn(String role, String name) throws Exception {
        return signInWith("sub-" + UUID.randomUUID(), role, name);
    }

    private JsonNode signInWith(String sub, String role, String name) throws Exception {
        var body = json.createObjectNode().put("idToken", tokenFor(sub, name));
        if (role != null) {
            body.put("role", role);
        }
        String response = mvc.perform(post("/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response);
    }
}
