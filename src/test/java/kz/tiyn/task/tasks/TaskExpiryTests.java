package kz.tiyn.task.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskExpired;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class TaskExpiryTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    TaskExpiryTicker ticker;

    @Autowired
    ApplicationEvents events;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    private Instant afterDeadline() {
        return Instant.now().plusSeconds(7200);
    }

    private long expiredEvents(UUID id) {
        return events.stream(TaskExpired.class)
                .filter(e -> e.taskId().equals(id))
                .count();
    }

    private void assertState(Family f, UUID id, String state) throws Exception {
        mvc.perform(get("/tasks/" + id).header("Authorization", f.parent().bearer()))
                .andExpect(jsonPath("$.state").value(state));
    }

    @Test
    void anOverdueAssignedTaskExpiresOnceEvenIfTheTickerRunsTwice() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);

        ticker.tick(afterDeadline());
        ticker.tick(afterDeadline());

        assertState(f, id, "EXPIRED");
        assertThat(expiredEvents(id)).isEqualTo(1);
    }

    @Test
    void aTaskBeforeItsDeadlineIsLeftAlone() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);

        ticker.tick(Instant.now());

        assertState(f, id, "ASSIGNED");
    }

    @Test
    void submittedAndCancelledTasksDoNotExpire() throws Exception {
        Family f = fx.linkedFamily();
        UUID submitted = fx.createTask(f, 100);
        UUID cancelled = fx.createTask(f, 100);
        fx.send("/tasks/" + submitted + "/submit", "{\"text\":\"a\"}", f.teen().bearer(), "Idempotency-Key", "e1");
        fx.send("/tasks/" + cancelled + "/cancel", "", f.parent().bearer());

        ticker.tick(afterDeadline());

        assertState(f, submitted, "SUBMITTED");
        assertState(f, cancelled, "CANCELLED");
        assertThat(expiredEvents(submitted) + expiredEvents(cancelled)).isZero();
    }

    @Test
    void anExpiredTaskCannotBeSubmitted() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);
        ticker.tick(afterDeadline());

        fx.send("/tasks/" + id + "/submit", "{\"text\":\"a\"}", f.teen().bearer(), "Idempotency-Key", "e2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("invalid_transition"));
    }
}
