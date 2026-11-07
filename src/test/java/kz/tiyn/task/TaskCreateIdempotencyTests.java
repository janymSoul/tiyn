package kz.tiyn.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
class TaskCreateIdempotencyTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    @Test
    void repeatingACreateWithTheSameKeyReturnsTheSameTaskWithTheSameStatus() throws Exception {
        Family f = fx.linkedFamily();
        String body = createBody(f, 100, "Chore");
        String key = UUID.randomUUID().toString();

        JsonNode first = created(f, body, key);
        JsonNode second = created(f, body, key);

        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(tasksOf(f)).hasSize(1);
    }

    @Test
    void aReplayShowsTheCurrentStateOfTheTask() throws Exception {
        Family f = fx.linkedFamily();
        String body = createBody(f, 100, "Chore");
        String key = UUID.randomUUID().toString();
        JsonNode first = created(f, body, key);

        fx.send("/tasks/" + first.get("id").asText() + "/cancel", "", f.parent().bearer())
                .andExpect(status().isOk());

        assertThat(created(f, body, key).get("state").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void theSameKeyWithADifferentRequestIsRejected() throws Exception {
        Family f = fx.linkedFamily();
        String key = UUID.randomUUID().toString();
        created(f, createBody(f, 100, "Chore"), key);

        fx.send("/tasks", createBody(f, 200, "Chore"), f.parent().bearer(), "Idempotency-Key", key)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_idempotency_key"));
        assertThat(tasksOf(f)).hasSize(1);
    }

    @Test
    void aMissingBlankOrOverlongKeyIsRejected() throws Exception {
        Family f = fx.linkedFamily();
        String body = createBody(f, 100, "Chore");

        for (String key : new String[] {null, " ", "k".repeat(101)}) {
            var request = post("/tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", f.parent().bearer())
                    .content(body);
            if (key != null) {
                request.header("Idempotency-Key", key);
            }
            mvc.perform(request)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("invalid_idempotency_key"));
        }
        assertThat(tasksOf(f)).isEmpty();
    }

    @Test
    void aFailedCreateDoesNotConsumeTheKey() throws Exception {
        Family f = fx.linkedFamily();
        String key = UUID.randomUUID().toString();

        fx.send("/tasks", createBody(f, 0, "Chore"), f.parent().bearer(), "Idempotency-Key", key)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_reward"));
        JsonNode retried = created(f, createBody(f, 100, "Chore"), key);

        assertThat(retried.get("title").asText()).isEqualTo("Chore");
        assertThat(tasksOf(f)).hasSize(1);
    }

    @Test
    void keysAreScopedPerUser() throws Exception {
        Family first = fx.linkedFamily();
        Family second = fx.linkedFamily();
        String key = UUID.randomUUID().toString();

        JsonNode a = created(first, createBody(first, 100, "Chore"), key);
        JsonNode b = created(second, createBody(second, 100, "Chore"), key);

        assertThat(a.get("id").asText()).isNotEqualTo(b.get("id").asText());
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateOneTask() throws Exception {
        Family f = fx.linkedFamily();
        String body = createBody(f, 100, "Chore");
        String key = UUID.randomUUID().toString();
        Callable<String> call = () -> fx.send("/tasks", body, f.parent().bearer(), "Idempotency-Key", key)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> results = pool.invokeAll(List.of(call, call, call, call));
            for (Future<String> result : results) {
                assertThat(json.readTree(result.get()).get("id").asText())
                        .isEqualTo(json.readTree(results.get(0).get()).get("id").asText());
            }
        } finally {
            pool.shutdown();
        }
        assertThat(tasksOf(f)).hasSize(1);
    }

    private JsonNode created(Family f, String body, String key) throws Exception {
        String response = fx.send("/tasks", body, f.parent().bearer(), "Idempotency-Key", key)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response);
    }

    private JsonNode tasksOf(Family f) throws Exception {
        String response = mvc.perform(
                        get("/tasks").header("Authorization", f.parent().bearer()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response).get("items");
    }

    private String createBody(Family f, long reward, String title) throws Exception {
        return json.writeValueAsString(Map.of(
                "teenId",
                f.teen().id(),
                "title",
                title,
                "rewardAmount",
                reward,
                "currency",
                "KZT",
                "deadlineAt",
                Instant.parse("2099-01-01T00:00:00Z").toString()));
    }
}
