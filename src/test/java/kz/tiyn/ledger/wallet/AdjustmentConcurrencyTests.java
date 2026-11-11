package kz.tiyn.ledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import com.fasterxml.jackson.databind.ObjectMapper;
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
class AdjustmentConcurrencyTests {

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

    private int adjust(Family f, String key, String kind, long amount) throws Exception {
        String body = "{\"kind\":\"" + kind + "\",\"amount\":" + amount + ",\"reason\":\"race\"}";
        return fx.send(
                        "/teens/" + f.teen().id() + "/adjustments",
                        body,
                        f.parent().bearer(),
                        "Idempotency-Key",
                        key)
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int entryCount(UUID teenId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger.ledger_entries WHERE teen_id = ?", Integer.class, teenId);
    }

    private void assertWalletMatchesJournal(UUID teenId) {
        assertThat(jdbc.queryForObject(
                        "SELECT balance_amount FROM ledger.wallets WHERE teen_id = ?", Long.class, teenId))
                .isEqualTo(jdbc.queryForObject(
                        "SELECT COALESCE(SUM(balance_delta), 0) FROM ledger.ledger_entries WHERE teen_id = ?",
                        Long.class,
                        teenId));
    }

    @Test
    void simultaneousAdjustmentsOnOneWalletAllApplyExactlyOnce() throws Exception {
        Family f = fx.linkedFamily();

        List<Integer> statuses =
                runConcurrently(index -> adjust(f, "k" + index, index % 2 == 0 ? "BONUS" : "PENALTY", 10_00 + index));

        assertThat(statuses).containsOnly(201);
        long expected = 0;
        for (int index = 0; index < THREADS; index++) {
            expected += (index % 2 == 0 ? 1 : -1) * (10_00L + index);
        }
        assertThat(balances.balance(f.teen().id()).balanceAmount()).isEqualTo(expected);
        assertThat(entryCount(f.teen().id())).isEqualTo(THREADS);
        assertWalletMatchesJournal(f.teen().id());
    }

    @Test
    void theSameKeySentConcurrentlyIsAppliedOnce() throws Exception {
        Family f = fx.linkedFamily();

        List<Integer> statuses = runConcurrently(index -> adjust(f, "one-key", "BONUS", 50_00));

        assertThat(statuses).containsOnly(201);
        assertThat(balances.balance(f.teen().id()).balanceAmount()).isEqualTo(50_00);
        assertThat(entryCount(f.teen().id())).isEqualTo(1);
        assertWalletMatchesJournal(f.teen().id());
    }

    @Test
    void adjustmentsRacingAGoalLockAndAPayoutRequestKeepTheWalletEqualToTheJournal() throws Exception {
        for (int round = 0; round < 4; round++) {
            Family f = fx.linkedFamily();
            adjust(f, "seed", "BONUS", 500_00);
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
                case 0 -> adjust(f, "p" + index, "PENALTY", 200_00);
                case 1 -> fx.send(
                                "/goals/" + goalId + "/lock",
                                "{\"amount\":30000}",
                                f.teen().bearer(),
                                "Idempotency-Key",
                                "g" + index)
                        .andReturn()
                        .getResponse()
                        .getStatus();
                default -> fx.send("/payouts", payout, f.teen().bearer(), "Idempotency-Key", "o" + index)
                        .andReturn()
                        .getResponse()
                        .getStatus();
            });

            assertWalletMatchesJournal(f.teen().id());
        }
    }

    @Test
    void anUnlinkRacingAnAdjustmentEitherAppliesItBeforeOrRejectsItAfter() throws Exception {
        for (int round = 0; round < 4; round++) {
            Family f = fx.linkedFamily();

            List<Integer> statuses = runConcurrently(index -> index == 0
                    ? mvc.perform(delete("/links/" + f.teen().id())
                                    .header("Authorization", f.parent().bearer()))
                            .andReturn()
                            .getResponse()
                            .getStatus()
                    : adjust(f, "u" + index, "PENALTY", 10_00));

            assertThat(statuses.subList(1, THREADS)).containsOnly(201, 403);
            long applied =
                    statuses.subList(1, THREADS).stream().filter(s -> s == 201).count();
            assertThat(entryCount(f.teen().id())).isEqualTo((int) applied);
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
