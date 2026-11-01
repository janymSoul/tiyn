package kz.tiyn.task;

import static org.assertj.core.api.Assertions.assertThat;

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
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
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

        runConcurrently(() -> fx.send(
                        "/tasks/" + id + "/submit", "{\"text\":\"a\"}", f.teen().bearer(), "Idempotency-Key", "dup")
                .andReturn()
                .getResponse()
                .getStatus());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM task.task_proofs WHERE task_id = ?", Integer.class, id))
                .isEqualTo(1);
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
