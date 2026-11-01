package kz.tiyn.identity;

import static kz.tiyn.support.FakeGoogleConfig.tokenFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@IntegrationTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class IdentityFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    ApplicationEvents events;

    @Autowired
    FamilyDirectory directory;

    @Test
    void parentSignInCreatesAccountWithJoinCodeAndSecondSignInReturnsTheSameAccount() throws Exception {
        String sub = uniqueSub();

        JsonNode first = signIn(sub, "PARENT", "Aigul");
        JsonNode second = signIn(sub, "TEEN", "Ignored");

        assertThat(first.at("/user/role").asText()).isEqualTo("PARENT");
        assertThat(first.at("/user/joinCode").asText()).matches("[A-Z2-9]{6}");
        assertThat(second.at("/user/id").asText()).isEqualTo(first.at("/user/id").asText());
        assertThat(second.at("/user/role").asText()).isEqualTo("PARENT");
        assertThat(second.at("/user/displayName").asText()).isEqualTo("Aigul");
    }

    @Test
    void teenHasNoJoinCodeAndDisplayNameFallsBackToTheGoogleProfile() throws Exception {
        JsonNode teen = signInRaw(tokenFor(uniqueSub(), "Dana From Google"), "TEEN", null);

        assertThat(teen.at("/user/displayName").asText()).isEqualTo("Dana From Google");
        assertThat(teen.at("/user/joinCode").isMissingNode()).isTrue();
    }

    @Test
    void signInErrorsUseTheDocumentedCodes() throws Exception {
        send("/auth/google", "{\"idToken\":\"garbage\"}", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_identity"));
        send("/auth/google", body(tokenFor(uniqueSub(), "x"), "ADMIN", "x"), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_role"));
        send("/auth/google", body(tokenFor(uniqueSub(), "x"), null, "x"), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_role"));
        send("/auth/google", body(tokenFor(uniqueSub(), null), "TEEN", "   "), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_display_name"));
        send("/auth/google", "{\"idToken\":\"x\",\"unknown\":1}", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_body"));
    }

    @Test
    void meReturnsTheCallersAccount() throws Exception {
        JsonNode parent = signIn(uniqueSub(), "PARENT", "Aigul");

        mvc.perform(get("/me").header("Authorization", bearer(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(parent.at("/user/id").asText()))
                .andExpect(jsonPath("$.joinCode").value(parent.at("/user/joinCode").asText()));
    }

    @Test
    void teenJoinsWithTheCodeCaseInsensitivelyAndRepeatingItIsANoOp() throws Exception {
        JsonNode parent = signIn(uniqueSub(), "PARENT", "Aigul");
        JsonNode teen = signIn(uniqueSub(), "TEEN", "Dana");
        String code = parent.at("/user/joinCode").asText();
        long linkedBefore = linkedEvents();

        send("/links/join", "{\"code\":\" " + code.toLowerCase() + " \"}", bearer(teen))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parent.displayName").value("Aigul"));
        send("/links/join", "{\"code\":\"" + code + "\"}", bearer(teen)).andExpect(status().isOk());

        assertThat(directory.areLinked(id(parent), id(teen))).isTrue();
        assertThat(linkedEvents() - linkedBefore).isEqualTo(1);
    }

    @Test
    void joinFailuresNeverRevealWhetherACodeExists() throws Exception {
        JsonNode teen = signIn(uniqueSub(), "TEEN", "Dana");

        for (String code : new String[] {"ZZZZZZ", "bad", "AB0CDE"}) {
            send("/links/join", "{\"code\":\"" + code + "\"}", bearer(teen))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("invalid_join_code"));
        }
    }

    @Test
    void onlyTeensCanJoinAndOnlyParentsCanUnlink() throws Exception {
        JsonNode parent = signIn(uniqueSub(), "PARENT", "Aigul");
        JsonNode teen = signIn(uniqueSub(), "TEEN", "Dana");
        String code = parent.at("/user/joinCode").asText();

        send("/links/join", "{\"code\":\"" + code + "\"}", bearer(parent))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mvc.perform(delete("/links/" + id(parent)).header("Authorization", bearer(teen)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unlinkRemovesTheLinkIsRepeatableAndRelinkingBumpsTheGenerationInTheEvents() throws Exception {
        JsonNode parent = signIn(uniqueSub(), "PARENT", "Aigul");
        JsonNode teen = signIn(uniqueSub(), "TEEN", "Dana");
        String joinBody = "{\"code\":\"" + parent.at("/user/joinCode").asText() + "\"}";

        send("/links/join", joinBody, bearer(teen)).andExpect(status().isOk());
        mvc.perform(delete("/links/" + id(teen)).header("Authorization", bearer(parent)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/links/" + id(teen)).header("Authorization", bearer(parent)))
                .andExpect(status().isNoContent());
        assertThat(directory.areLinked(id(parent), id(teen))).isFalse();
        send("/links/join", joinBody, bearer(teen)).andExpect(status().isOk());

        assertThat(directory.areLinked(id(parent), id(teen))).isTrue();
        assertThat(events.stream(FamilyLinked.class)
                        .filter(e -> e.teenId().equals(id(teen)))
                        .map(FamilyLinked::generation))
                .containsExactly(1, 2);
        assertThat(events.stream(FamilyUnlinked.class)
                        .filter(e -> e.teenId().equals(id(teen)))
                        .map(FamilyUnlinked::generation))
                .containsExactly(1);
    }

    @Test
    void unlinkingAnUnknownTeenIsNotFoundAndABadIdIsABadRequest() throws Exception {
        JsonNode parent = signIn(uniqueSub(), "PARENT", "Aigul");

        mvc.perform(delete("/links/" + UUID.randomUUID()).header("Authorization", bearer(parent)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mvc.perform(delete("/links/not-a-uuid").header("Authorization", bearer(parent)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_teen_id"));
    }

    @Test
    void oneCodeCanLinkSeveralTeensAndATeenCanHaveTwoParents() throws Exception {
        JsonNode mother = signIn(uniqueSub(), "PARENT", "Mother");
        JsonNode father = signIn(uniqueSub(), "PARENT", "Father");
        JsonNode first = signIn(uniqueSub(), "TEEN", "First");
        JsonNode second = signIn(uniqueSub(), "TEEN", "Second");

        for (JsonNode teen : new JsonNode[] {first, second}) {
            send("/links/join", codeBody(mother), bearer(teen)).andExpect(status().isOk());
        }
        send("/links/join", codeBody(father), bearer(first)).andExpect(status().isOk());

        assertThat(directory.areLinked(id(mother), id(first))).isTrue();
        assertThat(directory.areLinked(id(mother), id(second))).isTrue();
        assertThat(directory.areLinked(id(father), id(first))).isTrue();
        assertThat(directory.areLinked(id(father), id(second))).isFalse();
    }

    private long linkedEvents() {
        return events.stream(FamilyLinked.class).count();
    }

    private static String codeBody(JsonNode parent) {
        return "{\"code\":\"" + parent.at("/user/joinCode").asText() + "\"}";
    }

    private static String uniqueSub() {
        return "sub-" + UUID.randomUUID();
    }

    private static UUID id(JsonNode signInResponse) {
        return UUID.fromString(signInResponse.at("/user/id").asText());
    }

    private static String bearer(JsonNode signInResponse) {
        return "Bearer " + signInResponse.get("token").asText();
    }

    private JsonNode signIn(String sub, String role, String name) throws Exception {
        return signInRaw(tokenFor(sub, name), role, name);
    }

    private JsonNode signInRaw(String idToken, String role, String name) throws Exception {
        String response = send("/auth/google", body(idToken, role, name), null)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response);
    }

    private String body(String idToken, String role, String name) throws Exception {
        var node = json.createObjectNode().put("idToken", idToken);
        if (role != null) {
            node.put("role", role);
        }
        if (name != null) {
            node.put("displayName", name);
        }
        return json.writeValueAsString(node);
    }

    private ResultActions send(String path, String content, String authorization) throws Exception {
        MockHttpServletRequestBuilder request =
                MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(content);
        if (authorization != null) {
            request = request.header("Authorization", authorization);
        }
        return mvc.perform(request);
    }
}
