package kz.tiyn.task.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import kz.tiyn.identity.FamilyUnlinked;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
class UnlinkCleanupTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TaskService tasks;

    @Autowired
    FamilyUnlinkedListener listener;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    private String stateOf(UUID id) {
        return jdbc.queryForObject("SELECT state FROM task.tasks WHERE id = ?", String.class, id);
    }

    private UUID createSeries(Family f) throws Exception {
        String body = json.writeValueAsString(Map.of(
                "teenId", f.teen().id(),
                "title", "Walk dog",
                "rewardAmount", 100,
                "currency", "KZT",
                "recurrence", "DAILY",
                "dueTime", "23:59"));
        String response = fx.send("/task-series", body, f.parent().bearer())
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    private boolean seriesActive(UUID id) {
        return jdbc.queryForObject("SELECT active FROM task.task_series WHERE id = ?", Boolean.class, id);
    }

    @Test
    void unlinkingCancelsOpenTasksAndClosesSeriesButKeepsDecidedWorkAndOtherFamilies() throws Exception {
        Family f = fx.linkedFamily();
        Family untouched = fx.linkedFamily();
        UUID open = fx.createTask(f, 100);
        UUID submitted = fx.createTask(f, 100);
        UUID approved = fx.createTask(f, 100);
        UUID otherFamilyTask = fx.createTask(untouched, 100);
        UUID series = createSeries(f);
        fx.send("/tasks/" + submitted + "/submit", "{\"text\":\"a\"}", f.teen().bearer(), "Idempotency-Key", "u1");
        fx.send("/tasks/" + approved + "/submit", "{\"text\":\"a\"}", f.teen().bearer(), "Idempotency-Key", "u2");
        fx.send("/tasks/" + approved + "/approve", "", f.parent().bearer());

        mvc.perform(delete("/links/" + f.teen().id())
                        .header("Authorization", f.parent().bearer()))
                .andExpect(status().isNoContent());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(stateOf(open)).isEqualTo("CANCELLED");
            assertThat(stateOf(submitted)).isEqualTo("CANCELLED");
            assertThat(seriesActive(series)).isFalse();
        });
        assertThat(stateOf(approved)).isEqualTo("APPROVED");
        assertThat(stateOf(otherFamilyTask)).isEqualTo("ASSIGNED");
    }

    @Test
    void aReplayedUnlinkEventDoesNothingWhileTheFamilyIsLinkedAgain() throws Exception {
        Family f = fx.linkedFamily();
        UUID open = fx.createTask(f, 100);
        UUID series = createSeries(f);

        tasks.cancelOpenTasksAfterUnlink(f.parent().id(), f.teen().id());
        listener.on(new FamilyUnlinked(f.parent().id(), f.teen().id(), 1));

        assertThat(stateOf(open)).isEqualTo("ASSIGNED");
        assertThat(seriesActive(series)).isTrue();
    }
}
