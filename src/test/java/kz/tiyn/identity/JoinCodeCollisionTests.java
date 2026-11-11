package kz.tiyn.identity;

import static kz.tiyn.support.FakeGoogleConfig.tokenFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
class JoinCodeCollisionTests {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ScriptedRandom random;

    @Test
    void aRegeneratedCodeThatCollidesWithAnotherParentsCodeIsRedrawn() throws Exception {
        JsonNode mother = signIn("Mother");
        JsonNode father = signIn("Father");
        String fatherCode = father.at("/user/joinCode").asText();
        random.script(fatherCode);
        random.script("QWERTY");

        String body = mvc.perform(post("/me/join-code/regenerate")
                        .header("Authorization", "Bearer " + mother.get("token").asText()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(json.readTree(body).get("joinCode").asText()).isEqualTo("QWERTY");
        assertThat(codeOf(father)).isEqualTo(fatherCode);
        assertThat(codeOf(mother)).isEqualTo("QWERTY");
    }

    @Test
    void aRegenerationThatKeepsCollidingEndsAsConflictAndKeepsTheOldCode() throws Exception {
        JsonNode mother = signIn("Mother");
        JsonNode father = signIn("Father");
        String motherCode = mother.at("/user/joinCode").asText();
        for (int attempt = 0; attempt < 5; attempt++) {
            random.script(father.at("/user/joinCode").asText());
        }

        mvc.perform(post("/me/join-code/regenerate")
                        .header("Authorization", "Bearer " + mother.get("token").asText()))
                .andExpect(status().isConflict());

        assertThat(codeOf(mother)).isEqualTo(motherCode);
    }

    private String codeOf(JsonNode user) {
        return jdbc.queryForObject(
                "SELECT join_code FROM identity.users WHERE id = ?",
                String.class,
                UUID.fromString(user.at("/user/id").asText()));
    }

    private JsonNode signIn(String name) throws Exception {
        String body = json.writeValueAsString(json.createObjectNode()
                .put("idToken", tokenFor("sub-" + UUID.randomUUID(), name))
                .put("role", "PARENT")
                .put("displayName", name));
        String response = mvc.perform(post("/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response);
    }

    @TestConfiguration
    static class ScriptedRandomConfig {

        @Bean
        @Primary
        ScriptedRandom scriptedRandom() {
            return new ScriptedRandom();
        }
    }

    static class ScriptedRandom extends SecureRandom {

        private final transient Queue<Integer> scripted = new ArrayDeque<>();

        synchronized void script(String code) {
            code.chars().forEach(c -> scripted.add(ALPHABET.indexOf(c)));
        }

        @Override
        public synchronized int nextInt(int bound) {
            Integer next = scripted.poll();
            return next != null ? next : super.nextInt(bound);
        }
    }
}
