package kz.tiyn.identity;

import static kz.tiyn.support.FakeGoogleConfig.tokenFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.support.Invisible;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@IntegrationTest
@AutoConfigureMockMvc
class ProfileEditTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void parentRenameIsVisibleInMeAndKeepsEverythingElse() throws Exception {
        JsonNode parent = signIn("PARENT", "Aigul");

        patch("{\"displayName\":\"Aigerim\"}", bearer(parent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id(parent).toString()))
                .andExpect(jsonPath("$.role").value("PARENT"))
                .andExpect(jsonPath("$.displayName").value("Aigerim"))
                .andExpect(
                        jsonPath("$.joinCode").value(parent.at("/user/joinCode").asText()));

        me(bearer(parent))
                .andExpect(jsonPath("$.displayName").value("Aigerim"))
                .andExpect(
                        jsonPath("$.joinCode").value(parent.at("/user/joinCode").asText()));
    }

    @Test
    void teenRenameIsVisibleInMeAndHasNoJoinCode() throws Exception {
        JsonNode teen = signIn("TEEN", "Dana");

        patch("{\"displayName\":\"Danara\"}", bearer(teen))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("TEEN"))
                .andExpect(jsonPath("$.displayName").value("Danara"))
                .andExpect(jsonPath("$.joinCode").doesNotExist());
        me(bearer(teen)).andExpect(jsonPath("$.displayName").value("Danara"));
    }

    @Test
    void renamedUsersAreVisibleUnderTheNewNameToLinkedFamilyMembers() throws Exception {
        JsonNode parent = signIn("PARENT", "Mother");
        JsonNode teen = signIn("TEEN", "Dana");
        post("/links/join", "{\"code\":\"" + parent.at("/user/joinCode").asText() + "\"}", bearer(teen))
                .andExpect(status().isOk());

        patch("{\"displayName\":\"Mama Aigul\"}", bearer(parent)).andExpect(status().isOk());
        patch("{\"displayName\":\"Danara\"}", bearer(teen)).andExpect(status().isOk());

        family(bearer(teen)).andExpect(jsonPath("$.members[0].displayName").value("Mama Aigul"));
        family(bearer(parent)).andExpect(jsonPath("$.members[0].displayName").value("Danara"));
    }

    @Test
    void absentOrNullDisplayNameLeavesTheProfileUnchanged() throws Exception {
        JsonNode teen = signIn("TEEN", "Dana");

        patch("{}", bearer(teen))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Dana"));
        patch("{\"displayName\":null}", bearer(teen))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Dana"));
        me(bearer(teen)).andExpect(jsonPath("$.displayName").value("Dana"));
    }

    @Test
    void theNameIsTrimmedCollapsedAndStrippedOfControlAndBidiCharacters() throws Exception {
        JsonNode teen = signIn("TEEN", "Dana");
        String hostile = "  \\u202EDa\\u0000na\\u200B   Aibek\\t\\u2069 ";

        patch("{\"displayName\":\"" + hostile + "\"}", bearer(teen))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Dana Aibek"));
        assertThat(storedName(teen)).isEqualTo("Dana Aibek");
    }

    @Test
    void blankInvisibleOnlyAndTooLongNamesAreUnprocessableAndChangeNothing() throws Exception {
        JsonNode teen = signIn("TEEN", "Dana");
        String[] bad = {"", "   ", "\\u200B\\u202E", "\\u0000", "x".repeat(101), "x".repeat(100) + "\\u200B" + "y"};

        for (String name : bad) {
            patch("{\"displayName\":\"" + name + "\"}", bearer(teen))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("invalid_display_name"));
        }
        assertThat(storedName(teen)).isEqualTo("Dana");
    }

    @Test
    void aNameOfExactlyTheMaximumLengthIsAccepted() throws Exception {
        JsonNode teen = signIn("TEEN", "Dana");

        patch("{\"displayName\":\"" + "x".repeat(100) + "\"}", bearer(teen))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("x".repeat(100)));
    }

    @Test
    void immutableAndUnknownFieldsAreRejectedAsAnInvalidBodyAndChangeNothing() throws Exception {
        JsonNode parent = signIn("PARENT", "Aigul");
        String[] bodies = {
            "{\"role\":\"TEEN\"}",
            "{\"email\":\"a@b.kz\"}",
            "{\"idpSub\":\"other\"}",
            "{\"joinCode\":\"ZZZZZZ\"}",
            "{\"id\":\"" + UUID.randomUUID() + "\"}",
            "{\"displayName\":\"New\",\"role\":\"TEEN\"}",
            "{\"locale\":\"kk\"}"
        };

        for (String body : bodies) {
            patch(body, bearer(parent))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_body"));
        }
        me(bearer(parent))
                .andExpect(jsonPath("$.displayName").value("Aigul"))
                .andExpect(jsonPath("$.role").value("PARENT"))
                .andExpect(
                        jsonPath("$.joinCode").value(parent.at("/user/joinCode").asText()));
    }

    @Test
    void malformedBodyIsAnInvalidBody() throws Exception {
        JsonNode teen = signIn("TEEN", "Dana");

        patch("{\"displayName\":", bearer(teen))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_body"));
    }

    @Test
    void anonymousCallerIsUnauthenticated() throws Exception {
        mvc.perform(MockMvcRequestBuilders.patch("/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthenticated"));
    }

    @Test
    void aTokenOfAUserThatNoLongerExistsIsUnauthenticated() throws Exception {
        JsonNode teen = signIn("TEEN", "Dana");
        jdbc.update("DELETE FROM identity.users WHERE id = ?", id(teen));

        patch("{\"displayName\":\"Ghost\"}", bearer(teen))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthenticated"));
    }

    @Test
    void signInNormalisesTheRegistrationNameToo() throws Exception {
        JsonNode teen = signIn("TEEN", "  " + Invisible.RLO + "Da  na" + Invisible.ZWSP + " ");

        assertThat(teen.at("/user/displayName").asText()).isEqualTo("Da na");
    }

    @Test
    void concurrentPatchesLeaveOneOfTheSubmittedNamesAndAValidAccount() throws Exception {
        JsonNode parent = signIn("PARENT", "Aigul");
        int threads = 8;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            names.add("Name " + i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (String name : names) {
                results.add(pool.submit(() -> {
                    start.await();
                    return patch("{\"displayName\":\"" + name + "\"}", bearer(parent))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(names).contains(storedName(parent));
        me(bearer(parent))
                .andExpect(jsonPath("$.role").value("PARENT"))
                .andExpect(
                        jsonPath("$.joinCode").value(parent.at("/user/joinCode").asText()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.users WHERE id = ?", Integer.class, id(parent)))
                .isEqualTo(1);
    }

    private String storedName(JsonNode signInResponse) {
        return jdbc.queryForObject(
                "SELECT display_name FROM identity.users WHERE id = ?", String.class, id(signInResponse));
    }

    private ResultActions me(String authorization) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/me").header("Authorization", authorization))
                .andExpect(status().isOk());
    }

    private ResultActions family(String authorization) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/family").header("Authorization", authorization))
                .andExpect(status().isOk());
    }

    private ResultActions patch(String content, String authorization) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.patch("/me")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", authorization)
                .content(content));
    }

    private ResultActions post(String path, String content, String authorization) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", authorization)
                .content(content));
    }

    private JsonNode signIn(String role, String name) throws Exception {
        String body = json.writeValueAsString(json.createObjectNode()
                .put("idToken", tokenFor("sub-" + UUID.randomUUID(), name))
                .put("role", role)
                .put("displayName", name));
        String response = mvc.perform(MockMvcRequestBuilders.post("/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response);
    }

    private static UUID id(JsonNode signInResponse) {
        return UUID.fromString(signInResponse.at("/user/id").asText());
    }

    private static String bearer(JsonNode signInResponse) {
        return "Bearer " + signInResponse.get("token").asText();
    }
}
