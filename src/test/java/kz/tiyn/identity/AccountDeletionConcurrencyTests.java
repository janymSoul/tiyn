package kz.tiyn.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import kz.tiyn.task.TaskFixtures.Person;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
class AccountDeletionConcurrencyTests {

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
    void simultaneousDeletesOfOneAccountSucceedOnceAndTheRestAreUnauthenticated() throws Exception {
        Family f = fx.linkedFamily();

        List<Integer> statuses = runConcurrently(() -> deleteMe(f.parent()));

        assertThat(statuses).containsOnlyOnce(204).filteredOn(s -> s != 204).containsOnly(401);
        assertThat(jdbc.queryForObject(
                        "SELECT generation FROM identity.family_links WHERE parent_id = ? AND teen_id = ?",
                        Integer.class,
                        f.parent().id(),
                        f.teen().id()))
                .isEqualTo(1);
        assertThat(activeLinksOf(f.parent().id())).isZero();
    }

    @Test
    void deletingBothSidesOfALinkAtTheSameTimeLeavesNoActiveLinkAndNeitherCallFails() throws Exception {
        for (int round = 0; round < 5; round++) {
            Family f = fx.linkedFamily();

            List<Integer> statuses = runInParallel(() -> deleteMe(f.parent()), () -> deleteMe(f.teen()));

            assertThat(statuses).containsExactly(204, 204);
            assertThat(activeLinksOf(f.parent().id())).isZero();
        }
    }

    @Test
    void deletingWhileTheSameParentUnlinksAndATeenJoinsEndsWithNoActiveLinkToTheDeletedParent() throws Exception {
        for (int round = 0; round < 5; round++) {
            Family f = fx.linkedFamily();
            Person joiner = fx.unlinkedTeen();
            String code = jdbc.queryForObject(
                    "SELECT join_code FROM identity.users WHERE id = ?",
                    String.class,
                    f.parent().id());

            List<Integer> statuses = runInParallel(
                    () -> deleteMe(f.parent()), () -> unlink(f.parent(), f.teen()), () -> join(joiner, code));

            assertThat(statuses.get(0)).isEqualTo(204);
            assertThat(statuses.get(1)).isIn(204, 401, 404);
            assertThat(statuses.get(2)).isIn(200, 422);
            assertThat(activeLinksOf(f.parent().id())).isZero();
        }
    }

    private int deleteMe(Person who) throws Exception {
        return mvc.perform(delete("/me").header("Authorization", who.bearer()))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int unlink(Person parent, Person teen) throws Exception {
        return mvc.perform(delete("/links/" + teen.id()).header("Authorization", parent.bearer()))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int join(Person teen, String code) throws Exception {
        return mvc.perform(post("/links/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", teen.bearer())
                        .content("{\"code\":\"" + code + "\"}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int activeLinksOf(UUID parentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM identity.family_links WHERE parent_id = ? AND removed_at IS NULL",
                Integer.class,
                parentId);
    }

    private List<Integer> runConcurrently(Callable<Integer> work) throws Exception {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(work);
        }
        return run(tasks);
    }

    @SafeVarargs
    private List<Integer> runInParallel(Callable<Integer>... work) throws Exception {
        return run(List.of(work));
    }

    private List<Integer> run(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
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
}
