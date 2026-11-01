package kz.tiyn.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
class TaskConcurrencyTests {

    private static final int THREADS = 8;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    @Test
    void concurrentApprovalsByTheSameParentProduceOneApproval() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"a\"}", f.teen().bearer(), "Idempotency-Key", "c1");

        List<Integer> statuses = runConcurrently(
                () -> fx.send("/tasks/" + id + "/approve", "", f.parent().bearer())
                        .andReturn()
                        .getResponse()
                        .getStatus());

        assertThat(statuses).allMatch(s -> s == 200 || s == 409);
        assertThat(statuses).contains(200);
        assertThat(jdbc.queryForObject("SELECT state FROM task.tasks WHERE id = ?", String.class, id))
                .isEqualTo("APPROVED");
    }

    @Test
    void concurrentSubmitsWithTheSameKeyCreateOneProof() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);

        List<MockHttpServletResponse> responses = runConcurrently(() -> fx.send(
                        "/tasks/" + id + "/submit", "{\"text\":\"a\"}", f.teen().bearer(), "Idempotency-Key", "dup")
                .andReturn()
                .getResponse());

        assertThat(responses).allSatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo(200);
            assertThat(r.getContentAsString()).contains("\"state\":\"SUBMITTED\"");
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM task.task_proofs WHERE task_id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void aMilestoneRedoAndAChildRedoNeverLeaveTheMilestoneBeforeItsChild() throws Exception {
        Family f = fx.linkedFamily();
        for (int round = 0; round < 15; round++) {
            UUID milestone = fx.createItem(f, "MILESTONE", null, 7200);
            UUID child = fx.createItem(f, "TASK", milestone, 3600);
            fx.send(
                    "/tasks/" + milestone + "/submit",
                    "{\"text\":\"a\"}",
                    f.teen().bearer(),
                    "Idempotency-Key",
                    "m" + round);
            fx.send(
                    "/tasks/" + child + "/submit",
                    "{\"text\":\"a\"}",
                    f.teen().bearer(),
                    "Idempotency-Key",
                    "c" + round);
            String milestoneDeadline = "{\"deadlineAt\":\"" + Instant.now().plusSeconds(4000) + "\"}";
            String childDeadline = "{\"deadlineAt\":\"" + Instant.now().plusSeconds(5000) + "\"}";

            List<Integer> statuses =
                    runTogether(() -> redo(milestone, milestoneDeadline, f), () -> redo(child, childDeadline, f));

            assertThat(statuses).allMatch(status -> status == 200 || status == 422);
            assertThat(statuses).contains(200);
            assertThat(jdbc.queryForObject(
                            "SELECT m.deadline_at >= c.deadline_at FROM task.tasks m, task.tasks c "
                                    + "WHERE m.id = ? AND c.id = ?",
                            Boolean.class,
                            milestone,
                            child))
                    .isTrue();
        }
    }

    private int redo(UUID taskId, String body, Family f) throws Exception {
        return fx.send("/tasks/" + taskId + "/request-redo", body, f.parent().bearer())
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @SafeVarargs
    private List<Integer> runTogether(Callable<Integer>... work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(work.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> one : work) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return one.call();
                }));
            }
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private <T> List<T> runConcurrently(Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
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
