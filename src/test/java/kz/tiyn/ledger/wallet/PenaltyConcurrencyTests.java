package kz.tiyn.ledger.wallet;

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
import kz.tiyn.ledger.BalanceReader;
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
class PenaltyConcurrencyTests {

    private static final int THREADS = 8;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    BalanceReader balances;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PenaltyService penalties;

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

    private void assertWalletMatchesJournal(UUID teenId) {
        long balance =
                jdbc.queryForObject("SELECT balance_amount FROM ledger.wallets WHERE teen_id = ?", Long.class, teenId);
        long locked =
                jdbc.queryForObject("SELECT locked_amount FROM ledger.wallets WHERE teen_id = ?", Long.class, teenId);
        assertThat(balance)
                .isEqualTo(jdbc.queryForObject(
                        "SELECT COALESCE(SUM(balance_delta), 0) FROM ledger.ledger_entries WHERE teen_id = ?",
                        Long.class,
                        teenId));
        assertThat(locked)
                .isEqualTo(jdbc.queryForObject(
                        "SELECT COALESCE(SUM(locked_delta), 0) FROM ledger.ledger_entries WHERE teen_id = ?",
                        Long.class,
                        teenId));
    }

    @Test
    void theSameMissedTaskDeliveredConcurrentlyIsDebitedOnce() throws Exception {
        Family f = familyWith(500_00);
        UUID task = UUID.randomUUID();

        runConcurrently(index -> {
            penalties.applyMissedTaskPenalty(task, f.teen().id(), 100_00, "KZT");
            return null;
        });

        assertThat(balances.balance(f.teen().id()).balanceAmount()).isEqualTo(400_00);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ledger.ledger_entries WHERE ref_id = ? AND kind = 'PENALTY'",
                        Integer.class,
                        task))
                .isEqualTo(1);
        assertWalletMatchesJournal(f.teen().id());
    }

    @Test
    void penaltiesForDifferentTasksAddUpExactly() throws Exception {
        Family f = familyWith(500_00);

        runConcurrently(index -> {
            penalties.applyMissedTaskPenalty(UUID.randomUUID(), f.teen().id(), 10_00, "KZT");
            return null;
        });

        assertThat(balances.balance(f.teen().id()).balanceAmount()).isEqualTo(500_00 - THREADS * 10_00);
        assertWalletMatchesJournal(f.teen().id());
    }

    @Test
    void penaltiesRacingAGoalLockAndAPayoutKeepTheWalletEqualToTheJournal() throws Exception {
        for (int round = 0; round < 8; round++) {
            Family f = familyWith(500_00);
            String goal = fx.send(
                            "/goals",
                            "{\"name\":\"g\",\"targetAmount\":100000}",
                            f.teen().bearer())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            UUID goalId = UUID.fromString(json.readTree(goal).get("id").asText());
            String payout = "{\"parentId\":\"" + f.parent().id() + "\",\"amount\":30000}";

            runConcurrently(index -> switch (index % 3) {
                case 0 -> {
                    penalties.applyMissedTaskPenalty(UUID.randomUUID(), f.teen().id(), 200_00, "KZT");
                    yield 0;
                }
                case 1 -> fx.send(
                                "/goals/" + goalId + "/lock",
                                "{\"amount\":30000}",
                                f.teen().bearer(),
                                "Idempotency-Key",
                                "g" + index)
                        .andReturn()
                        .getResponse()
                        .getStatus();
                default -> fx.send("/payouts", payout, f.teen().bearer(), "Idempotency-Key", "p" + index)
                        .andReturn()
                        .getResponse()
                        .getStatus();
            });

            assertWalletMatchesJournal(f.teen().id());
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
