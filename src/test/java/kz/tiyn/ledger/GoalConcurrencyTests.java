package kz.tiyn.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
class GoalConcurrencyTests {

    private static final int THREADS = 8;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    BalanceReader balances;

    @Autowired
    JdbcTemplate jdbc;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    private Family familyWith(long reward) throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, reward);
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"done\"}", f.teen().bearer(), "Idempotency-Key", "s-" + id)
                .andExpect(status().isOk());
        fx.send("/tasks/" + id + "/approve", "", f.parent().bearer()).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(balances.balance(f.teen().id()).balanceAmount())
                        .isEqualTo(reward));
        return f;
    }

    private UUID createGoal(Family f, long target) throws Exception {
        String response = fx.send(
                        "/goals",
                        "{\"name\":\"g\",\"targetAmount\":" + target + "}",
                        f.teen().bearer())
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    private int lock(Family f, UUID goal, long amount, String key) throws Exception {
        return fx.send(
                        "/goals/" + goal + "/lock",
                        "{\"amount\":" + amount + "}",
                        f.teen().bearer(),
                        "Idempotency-Key",
                        key)
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private void assertLockedMatchesGoals(Family f) {
        long locked = jdbc.queryForObject(
                "SELECT locked_amount FROM ledger.wallets WHERE teen_id = ?",
                Long.class,
                f.teen().id());
        long saved = jdbc.queryForObject(
                "SELECT COALESCE(SUM(locked_delta), 0) FROM ledger.ledger_entries WHERE teen_id = ?",
                Long.class,
                f.teen().id());
        long balance = jdbc.queryForObject(
                "SELECT balance_amount FROM ledger.wallets WHERE teen_id = ?",
                Long.class,
                f.teen().id());
        assertThat(locked).isEqualTo(saved).isLessThanOrEqualTo(balance);
    }

    @Test
    void concurrentLocksNeverExceedWhatIsAvailable() throws Exception {
        Family f = familyWith(100);
        UUID goal = createGoal(f, 1000);

        List<Integer> statuses = runConcurrently(index -> lock(f, goal, 30, "k" + index));

        assertThat(statuses).filteredOn(s -> s == 200).hasSize(3);
        assertThat(statuses).filteredOn(s -> s == 422).hasSize(THREADS - 3);
        Balance balance = balances.balance(f.teen().id());
        assertThat(balance.lockedAmount()).isEqualTo(90);
        assertThat(balance.availableAmount()).isEqualTo(10);
        assertLockedMatchesGoals(f);
    }

    @Test
    void concurrentLocksIntoDifferentGoalsStayConsistent() throws Exception {
        Family f = familyWith(100);
        List<UUID> goals = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            goals.add(createGoal(f, 1000));
        }

        List<Integer> statuses = runConcurrently(index -> lock(f, goals.get(index), 30, "k" + index));

        assertThat(statuses).filteredOn(s -> s == 200).hasSize(3);
        assertThat(balances.balance(f.teen().id()).lockedAmount()).isEqualTo(90);
        assertLockedMatchesGoals(f);
    }

    @Test
    void aLockRacingAPayoutRequestNeverOverspends() throws Exception {
        for (int round = 0; round < 10; round++) {
            Family f = familyWith(500_00);
            UUID goal = createGoal(f, 1_000_00);
            String payout = "{\"parentId\":\"" + f.parent().id() + "\",\"amount\":30000}";

            List<Integer> statuses = runConcurrently(index -> index % 2 == 0
                    ? lock(f, goal, 30000, "r" + index)
                    : fx.send("/payouts", payout, f.teen().bearer(), "Idempotency-Key", "p" + index)
                            .andReturn()
                            .getResponse()
                            .getStatus());

            assertThat(statuses).allMatch(s -> s == 200 || s == 201 || s == 409 || s == 422);
            Balance balance = balances.balance(f.teen().id());
            assertThat(balance.availableAmount()).isBetween(0L, 500_00L);
            assertThat(balance.lockedAmount()).isLessThanOrEqualTo(500_00L);
            assertLockedMatchesGoals(f);
        }
    }

    private interface Work<T> {
        T run(int index) throws Exception;
    }

    private <T> List<T> runConcurrently(Work<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                int index = i;
                Callable<T> task = () -> {
                    start.await();
                    return work.run(index);
                };
                futures.add(pool.submit(task));
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
