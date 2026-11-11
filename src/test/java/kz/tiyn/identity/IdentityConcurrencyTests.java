package kz.tiyn.identity;

import static kz.tiyn.support.FakeGoogleConfig.tokenFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import kz.tiyn.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
class IdentityConcurrencyTests {

    private static final int THREADS = 8;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    FamilyDirectory directory;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void concurrentFirstSignInsOfTheSameGoogleAccountCreateOneUser() throws Exception {
        String sub = "sub-" + UUID.randomUUID();

        List<JsonNode> results = runConcurrently(() -> signIn(sub, "PARENT", "Aigul"));

        assertThat(results.stream().map(r -> r.at("/user/id").asText()).collect(Collectors.toSet()))
                .hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.users WHERE idp_sub = ?", Integer.class, sub))
                .isEqualTo(1);
    }

    @Test
    void concurrentJoinsOfTheSamePairCreateOneLink() throws Exception {
        JsonNode parent = signIn("sub-" + UUID.randomUUID(), "PARENT", "P");
        JsonNode teen = signIn("sub-" + UUID.randomUUID(), "TEEN", "T");
        UUID parentId = UUID.fromString(parent.at("/user/id").asText());
        UUID teenId = UUID.fromString(teen.at("/user/id").asText());

        runConcurrently(() -> {
            mvc.perform(post("/links/join")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(
                                    "Authorization",
                                    "Bearer " + teen.get("token").asText())
                            .content(
                                    "{\"code\":\"" + parent.at("/user/joinCode").asText() + "\"}"))
                    .andReturn();
            return null;
        });

        assertThat(directory.areLinked(parentId, teenId)).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM identity.family_links WHERE parent_id = ? AND teen_id = ?",
                        Integer.class,
                        parentId,
                        teenId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT generation FROM identity.family_links WHERE teen_id = ?", Integer.class, teenId))
                .isEqualTo(1);
    }

    @Test
    void concurrentRefreshesOfTheSameTokenProduceExactlyOneSuccessAndEndTheSession() throws Exception {
        JsonNode session = signIn("sub-" + UUID.randomUUID(), "TEEN", "Dana");
        String refreshToken = session.get("refreshToken").asText();
        UUID userId = UUID.fromString(session.at("/user/id").asText());

        List<Integer> statuses = runConcurrently(() -> mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(json.createObjectNode().put("refreshToken", refreshToken))))
                .andReturn()
                .getResponse()
                .getStatus());

        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 401)).hasSize(THREADS - 1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM identity.refresh_tokens WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM identity.refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                        Integer.class,
                        userId))
                .isZero();
    }

    private JsonNode signIn(String sub, String role, String name) throws Exception {
        String body = json.writeValueAsString(json.createObjectNode()
                .put("idToken", tokenFor(sub, name))
                .put("role", role)
                .put("displayName", name));
        String response = mvc.perform(post("/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response);
    }

    private <T> List<T> runConcurrently(Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
