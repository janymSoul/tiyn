package kz.tiyn.identity;

import static kz.tiyn.support.FakeGoogleConfig.tokenFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import kz.tiyn.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class JoinRequestConcurrencyTests {

    private static final int THREADS = 8;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FamilyDirectory directory;

    @Autowired
    ApplicationEvents events;

    @Test
    void concurrentRequestsFromOneTeenToOneParentCreateOnePendingRequest() throws Exception {
        JsonNode parent = approvingParent();
        JsonNode teen = signIn("TEEN", "T");

        List<Integer> statuses = runConcurrently(() -> join(teen, parent));

        assertThat(statuses).contains(202).containsOnly(202, 429);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM identity.join_requests WHERE parent_id = ? AND teen_id = ?"
                                + " AND state = 'PENDING'",
                        Integer.class,
                        id(parent),
                        id(teen)))
                .isEqualTo(1);
        assertThat(events.stream(JoinRequested.class).filter(e -> e.teenId().equals(id(teen))))
                .hasSize(1);
    }

    @Test
    void concurrentApprovalsOfOneRequestCreateOneLinkAndPublishOneFamilyLinked() throws Exception {
        JsonNode parent = approvingParent();
        JsonNode teen = signIn("TEEN", "T");
        join(teen, parent);
        UUID request =
                jdbc.queryForObject("SELECT id FROM identity.join_requests WHERE teen_id = ?", UUID.class, id(teen));

        List<Integer> statuses = runConcurrently(() -> decide(parent, request, "approve"));

        assertThat(statuses).containsOnly(200);
        assertThat(directory.areLinked(id(parent), id(teen))).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT generation FROM identity.family_links WHERE parent_id = ? AND teen_id = ?",
                        Integer.class,
                        id(parent),
                        id(teen)))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM identity.join_requests WHERE id = ?", String.class, request))
                .isEqualTo("APPROVED");
        assertThat(events.stream(FamilyLinked.class).filter(e -> e.teenId().equals(id(teen))))
                .hasSize(1);
    }

    @Test
    void anApprovalRacingADeclineHasOneWinnerAndTheLoserIsToldTheRequestIsClosed() throws Exception {
        JsonNode parent = approvingParent();
        JsonNode teen = signIn("TEEN", "T");
        join(teen, parent);
        UUID request =
                jdbc.queryForObject("SELECT id FROM identity.join_requests WHERE teen_id = ?", UUID.class, id(teen));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> approve = pool.submit(() -> {
                start.await();
                return decide(parent, request, "approve");
            });
            Future<Integer> decline = pool.submit(() -> {
                start.await();
                return decide(parent, request, "decline");
            });
            start.countDown();

            List<Integer> statuses = List.of(approve.get(), decline.get());

            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            String state =
                    jdbc.queryForObject("SELECT state FROM identity.join_requests WHERE id = ?", String.class, request);
            assertThat(directory.areLinked(id(parent), id(teen))).isEqualTo("APPROVED".equals(state));
        } finally {
            pool.shutdownNow();
        }
    }

    private JsonNode approvingParent() throws Exception {
        JsonNode parent = signIn("PARENT", "P");
        mvc.perform(put("/me/join-approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", bearer(parent))
                        .content("{\"required\":true}"))
                .andReturn();
        return parent;
    }

    private int join(JsonNode teen, JsonNode parent) throws Exception {
        return mvc.perform(post("/links/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", bearer(teen))
                        .content("{\"code\":\"" + parent.at("/user/joinCode").asText() + "\"}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int decide(JsonNode parent, UUID request, String action) throws Exception {
        return mvc.perform(post("/links/requests/" + request + "/" + action).header("Authorization", bearer(parent)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private JsonNode signIn(String role, String name) throws Exception {
        String body = json.writeValueAsString(json.createObjectNode()
                .put("idToken", tokenFor("sub-" + UUID.randomUUID(), name))
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

    private static UUID id(JsonNode signInResponse) {
        return UUID.fromString(signInResponse.at("/user/id").asText());
    }

    private static String bearer(JsonNode signInResponse) {
        return "Bearer " + signInResponse.get("token").asText();
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
