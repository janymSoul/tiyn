package kz.tiyn.task;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.support.TestImages;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@AutoConfigureMockMvc
class TaskProofReadTests {

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
    void theOwningTeenReadsTheirTextProof() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"scrubbed\"}", f.teen().bearer(), "Idempotency-Key", "t1")
                .andExpect(status().isOk());

        proofs(id, f.teen().bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].attemptNo").value(1))
                .andExpect(jsonPath("$[0].text").value("scrubbed"))
                .andExpect(jsonPath("$[0].imageUrl").doesNotExist());
    }

    @Test
    void theOwningTeenGetsAViewUrlForTheirImageProof() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);
        mvc.perform(multipart("/tasks/" + id + "/submit")
                        .file(new MockMultipartFile("file", "p.png", "image/png", TestImages.png(8, 8)))
                        .header("Authorization", f.teen().bearer())
                        .header("Idempotency-Key", "i1"))
                .andExpect(status().isOk());

        proofs(id, f.teen().bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].imageUrl").isNotEmpty())
                .andExpect(jsonPath("$[0].text").doesNotExist());
    }

    @Test
    void theTeenSeesEveryAttemptAfterARedo() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"a\"}", f.teen().bearer(), "Idempotency-Key", "a1");
        fx.send("/tasks/" + id + "/request-redo", "{}", f.parent().bearer()).andExpect(status().isOk());
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"b\"}", f.teen().bearer(), "Idempotency-Key", "a2");

        proofs(id, f.teen().bearer())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].attemptNo").value(1))
                .andExpect(jsonPath("$[1].attemptNo").value(2));
    }

    @Test
    void aTaskWithoutSubmissionsHasNoProofs() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);

        proofs(id, f.teen().bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        proofs(id, f.parent().bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theLinkedParentStillReadsProofs() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"done\"}", f.teen().bearer(), "Idempotency-Key", "p1");

        proofs(id, f.parent().bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].text").value("done"));
    }

    @Test
    void anotherTeenCannotReadSomeoneElsesProofs() throws Exception {
        Family f = fx.linkedFamily();
        Family other = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"done\"}", f.teen().bearer(), "Idempotency-Key", "o1");

        proofs(id, other.teen().bearer())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        proofs(id, fx.unlinkedTeen().bearer()).andExpect(status().isForbidden());
    }

    @Test
    void aForeignParentCannotReadProofs() throws Exception {
        Family f = fx.linkedFamily();
        Family other = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"done\"}", f.teen().bearer(), "Idempotency-Key", "f1");

        proofs(id, other.parent().bearer())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void aParentWhoUnlinkedTheTeenCannotReadProofs() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"done\"}", f.teen().bearer(), "Idempotency-Key", "u1");
        mvc.perform(delete("/links/" + f.teen().id())
                        .header("Authorization", f.parent().bearer()))
                .andExpect(status().isNoContent());

        proofs(id, f.parent().bearer()).andExpect(status().isForbidden());
    }

    @Test
    void anUnknownTaskIsNotFound() throws Exception {
        Family f = fx.linkedFamily();

        proofs(UUID.randomUUID(), f.teen().bearer())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    private ResultActions proofs(UUID taskId, String bearer) throws Exception {
        return mvc.perform(get("/tasks/" + taskId + "/proofs").header("Authorization", bearer));
    }
}
