package kz.tiyn.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.support.TestImages;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class TaskFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    ApplicationEvents events;

    @Autowired
    PendingReader pending;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    @Test
    void createSubmitApproveEmitsTheEventsInOrder() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 500_00);

        fx.send("/tasks/" + id + "/submit", "{\"text\":\"done\"}", f.teen().bearer(), "Idempotency-Key", "k1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUBMITTED"));
        assertThat(pending.pendingReward(f.teen().id())).isEqualTo(500_00);

        fx.send("/tasks/" + id + "/approve", "", f.parent().bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPROVED"));
        fx.send("/tasks/" + id + "/approve", "", f.parent().bearer()).andExpect(status().isOk());

        assertThat(pending.pendingReward(f.teen().id())).isZero();
        assertThat(events.stream(TaskAssigned.class).filter(e -> e.taskId().equals(id)))
                .hasSize(1);
        assertThat(events.stream(TaskSubmitted.class).filter(e -> e.taskId().equals(id)))
                .hasSize(1);
        assertThat(events.stream(TaskApproved.class).filter(e -> e.taskId().equals(id)))
                .singleElement()
                .satisfies(e -> assertThat(e.rewardAmount()).isEqualTo(500_00));
    }

    @Test
    void imageProofIsStoredAndParentGetsAViewUrl() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);

        mvc.perform(multipart("/tasks/" + id + "/submit")
                        .file(new MockMultipartFile("file", "p.png", "image/png", TestImages.png(8, 8)))
                        .header("Authorization", f.teen().bearer())
                        .header("Idempotency-Key", "img-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUBMITTED"));

        mvc.perform(get("/tasks/" + id + "/proofs")
                        .header("Authorization", f.parent().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attemptNo").value(1))
                .andExpect(jsonPath("$[0].imageUrl").isNotEmpty());
    }

    @Test
    void unsupportedImagesAreRejected() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);

        mvc.perform(multipart("/tasks/" + id + "/submit")
                        .file(new MockMultipartFile("file", "p.gif", "image/gif", new byte[] {1}))
                        .header("Authorization", f.teen().bearer())
                        .header("Idempotency-Key", "img-2"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("unsupported_proof"));
    }

    @Test
    void submitReplayWithTheSameKeyIsANoOp() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);
        long before = events.stream(TaskSubmitted.class).count();

        fx.send("/tasks/" + id + "/submit", "{\"text\":\"a\"}", f.teen().bearer(), "Idempotency-Key", "same")
                .andExpect(status().isOk());
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"a\"}", f.teen().bearer(), "Idempotency-Key", "same")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUBMITTED"));

        assertThat(events.stream(TaskSubmitted.class).count() - before).isEqualTo(1);
    }

    @Test
    void redoLetsTheTeenSubmitAgainAndRejectIsTerminal() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"a\"}", f.teen().bearer(), "Idempotency-Key", "r1");

        fx.send(
                        "/tasks/" + id + "/request-redo",
                        "{\"note\":\"again\"}",
                        f.parent().bearer())
                .andExpect(jsonPath("$.state").value("ASSIGNED"))
                .andExpect(jsonPath("$.decisionNote").value("again"));
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"b\"}", f.teen().bearer(), "Idempotency-Key", "r2")
                .andExpect(status().isOk());
        fx.send("/tasks/" + id + "/reject", "{}", f.parent().bearer())
                .andExpect(jsonPath("$.state").value("REJECTED"));
        fx.send("/tasks/" + id + "/approve", "", f.parent().bearer())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("invalid_transition"));
    }

    @Test
    void reusingTheSameKeyAfterARedoStartsANewAttempt() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"a\"}", f.teen().bearer(), "Idempotency-Key", "reused");
        fx.send("/tasks/" + id + "/request-redo", "{}", f.parent().bearer());

        fx.send("/tasks/" + id + "/submit", "{\"text\":\"b\"}", f.teen().bearer(), "Idempotency-Key", "reused")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUBMITTED"));
    }

    @Test
    void redoWithANewDeadlineMovesItAndRejectsAPastOne() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"a\"}", f.teen().bearer(), "Idempotency-Key", "d1");
        String later = Instant.now().plusSeconds(86400).toString();

        fx.send(
                        "/tasks/" + id + "/request-redo",
                        "{\"deadlineAt\":\"2020-01-01T00:00:00Z\"}",
                        f.parent().bearer())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_deadline"));
        fx.send(
                        "/tasks/" + id + "/request-redo",
                        "{\"deadlineAt\":\"" + later + "\"}",
                        f.parent().bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ASSIGNED"))
                .andExpect(jsonPath("$.deadlineAt").value(later));
    }

    @Test
    void milestoneTracksProgressAndCancellingItCancelsOpenTasks() throws Exception {
        Family f = fx.linkedFamily();
        UUID milestone = fx.createItem(f, "MILESTONE", null, 7200);
        UUID done = fx.createItem(f, "TASK", milestone, 3600);
        UUID open = fx.createItem(f, "TASK", milestone, 3600);
        fx.send("/tasks/" + done + "/submit", "{\"text\":\"a\"}", f.teen().bearer(), "Idempotency-Key", "m1");
        fx.send("/tasks/" + done + "/approve", "", f.parent().bearer());

        mvc.perform(get("/tasks/" + milestone)
                        .header("Authorization", f.parent().bearer()))
                .andExpect(jsonPath("$.approvedTasks").value(1))
                .andExpect(jsonPath("$.totalTasks").value(2));
        mvc.perform(get("/tasks/" + done).header("Authorization", f.parent().bearer()))
                .andExpect(jsonPath("$.milestoneId").value(milestone.toString()))
                .andExpect(jsonPath("$.totalTasks").doesNotExist());

        fx.send("/tasks/" + milestone + "/cancel", "", f.parent().bearer())
                .andExpect(jsonPath("$.state").value("CANCELLED"));

        mvc.perform(get("/tasks/" + open).header("Authorization", f.parent().bearer()))
                .andExpect(jsonPath("$.state").value("CANCELLED"));
        mvc.perform(get("/tasks/" + done).header("Authorization", f.parent().bearer()))
                .andExpect(jsonPath("$.state").value("APPROVED"));
        mvc.perform(get("/tasks/" + milestone)
                        .header("Authorization", f.parent().bearer()))
                .andExpect(jsonPath("$.totalTasks").value(1));
    }

    @Test
    void cancellingAMilestoneTwiceIsANoOp() throws Exception {
        Family f = fx.linkedFamily();
        UUID milestone = fx.createItem(f, "MILESTONE", null, 7200);
        UUID child = fx.createItem(f, "TASK", milestone, 3600);
        fx.send("/tasks/" + child + "/submit", "{\"text\":\"a\"}", f.teen().bearer(), "Idempotency-Key", "x1");
        fx.send("/tasks/" + milestone + "/cancel", "", f.parent().bearer()).andExpect(status().isOk());

        fx.send("/tasks/" + milestone + "/cancel", "", f.parent().bearer()).andExpect(status().isOk());

        mvc.perform(get("/tasks/" + child).header("Authorization", f.parent().bearer()))
                .andExpect(jsonPath("$.state").value("CANCELLED"));
    }

    @Test
    void invalidMilestoneLinksAreRejected() throws Exception {
        Family f = fx.linkedFamily();
        Family other = fx.linkedFamily();
        UUID milestone = fx.createItem(f, "MILESTONE", null, 7200);
        UUID plain = fx.createItem(f, "TASK", null, 3600);

        fx.send(
                        "/tasks",
                        fx.bodyFor(f, "TASK", UUID.randomUUID(), 3600),
                        f.parent().bearer())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_milestone"));
        fx.send("/tasks", fx.bodyFor(f, "TASK", plain, 3600), f.parent().bearer())
                .andExpect(jsonPath("$.error").value("invalid_milestone"));
        fx.send("/tasks", fx.bodyFor(f, "TASK", milestone, 9000), f.parent().bearer())
                .andExpect(jsonPath("$.error").value("invalid_milestone"));
        fx.send(
                        "/tasks",
                        fx.bodyFor(other, "TASK", milestone, 3600),
                        other.parent().bearer())
                .andExpect(jsonPath("$.error").value("invalid_milestone"));
    }

    @Test
    void uploadProblemsAreClientErrorsNotServerErrors() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);

        mvc.perform(multipart("/tasks/" + id + "/submit")
                        .header("Authorization", f.teen().bearer())
                        .header("Idempotency-Key", "no-file"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        mvc.perform(multipart("/tasks/" + id + "/submit")
                        .file(new MockMultipartFile("file", "p.png", "image/png", new byte[5 * 1024 * 1024 + 1]))
                        .header("Authorization", f.teen().bearer())
                        .header("Idempotency-Key", "too-big"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("unsupported_proof"));
    }

    @Test
    void aMilestoneRedoCannotEndBeforeItsOpenTasks() throws Exception {
        Family f = fx.linkedFamily();
        UUID milestone = fx.createItem(f, "MILESTONE", null, 7200);
        fx.createItem(f, "TASK", milestone, 3600);
        fx.send("/tasks/" + milestone + "/submit", "{\"text\":\"a\"}", f.teen().bearer(), "Idempotency-Key", "ms1");

        fx.send(
                        "/tasks/" + milestone + "/request-redo",
                        "{\"deadlineAt\":\"" + Instant.now().plusSeconds(1800) + "\"}",
                        f.parent().bearer())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_deadline"));
        fx.send(
                        "/tasks/" + milestone + "/request-redo",
                        "{\"deadlineAt\":\"" + Instant.now().plusSeconds(5000) + "\"}",
                        f.parent().bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ASSIGNED"));
    }

    @Test
    void anUnknownKindIsRejectedWithItsOwnCode() throws Exception {
        Family f = fx.linkedFamily();

        fx.send(
                        "/tasks",
                        createBody(f.teen().id()).replace("{", "{\"kind\":\"CHORE\","),
                        f.parent().bearer())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_kind"));
    }

    @Test
    void cancelIsIdempotentAndBlocksSubmission() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);

        fx.send("/tasks/" + id + "/cancel", "", f.parent().bearer()).andExpect(status().isOk());
        fx.send("/tasks/" + id + "/cancel", "", f.parent().bearer()).andExpect(status().isOk());
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"a\"}", f.teen().bearer(), "Idempotency-Key", "c1")
                .andExpect(status().isConflict());

        assertThat(events.stream(TaskCancelled.class).filter(e -> e.taskId().equals(id)))
                .hasSize(1);
    }

    @Test
    void authorisationIsEnforced() throws Exception {
        Family f = fx.linkedFamily();
        Family other = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);

        fx.send("/tasks/" + id + "/approve", "", f.teen().bearer()).andExpect(status().isForbidden());
        fx.send("/tasks/" + id + "/approve", "", other.parent().bearer()).andExpect(status().isForbidden());
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"a\"}", other.teen().bearer(), "Idempotency-Key", "x")
                .andExpect(status().isForbidden());
        mvc.perform(get("/tasks/" + id).header("Authorization", other.teen().bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/tasks/" + UUID.randomUUID())
                        .header("Authorization", f.parent().bearer()))
                .andExpect(status().isNotFound());
        fx.send("/tasks", createBody(fx.unlinkedTeen().id()), f.parent().bearer())
                .andExpect(status().isForbidden());
        fx.send("/tasks", createBody(f.teen().id()), f.teen().bearer()).andExpect(status().isForbidden());
    }

    @Test
    void validationErrorsUseTheDocumentedCodes() throws Exception {
        Family f = fx.linkedFamily();

        fx.send("/tasks", createBody(f.teen().id(), 0, "Chore"), f.parent().bearer())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_reward"));
        fx.send("/tasks", createBody(f.teen().id(), 100, " "), f.parent().bearer())
                .andExpect(jsonPath("$.error").value("invalid_title"));
        fx.send("/tasks", "{\"title\":\"x\"}", f.parent().bearer())
                .andExpect(jsonPath("$.error").value("teen_required"));
        UUID id = fx.createTask(f, 100);
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"a\"}", f.teen().bearer())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_idempotency_key"));
    }

    @Test
    void listFiltersByStateAndScopesTeenToOwnTasks() throws Exception {
        Family f = fx.linkedFamily();
        UUID open = fx.createTask(f, 100);
        UUID done = fx.createTask(f, 200);
        fx.send("/tasks/" + done + "/submit", "{\"text\":\"a\"}", f.teen().bearer(), "Idempotency-Key", "l1");

        mvc.perform(get("/tasks?state=SUBMITTED")
                        .header("Authorization", f.parent().bearer()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(done.toString()));
        mvc.perform(get("/tasks").header("Authorization", f.teen().bearer()))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/tasks?state=NOPE").header("Authorization", f.parent().bearer()))
                .andExpect(status().isUnprocessableEntity());
        assertThat(open).isNotEqualTo(done);
    }

    private String createBody(UUID teenId) throws Exception {
        return createBody(teenId, 100, "Chore");
    }

    private String createBody(UUID teenId, long reward, String title) throws Exception {
        return json.writeValueAsString(Map.of(
                "teenId", teenId,
                "title", title,
                "rewardAmount", reward,
                "currency", "KZT",
                "deadlineAt", Instant.now().plusSeconds(3600).toString()));
    }
}
