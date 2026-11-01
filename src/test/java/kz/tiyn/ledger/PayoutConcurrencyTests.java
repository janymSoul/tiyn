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
class PayoutConcurrencyTests {

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

    private Family richFamily() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 500_00);
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"done\"}", f.teen().bearer(), "Idempotency-Key", "s-" + id)
                .andExpect(status().isOk());
        fx.send("/tasks/" + id + "/approve", "", f.parent().bearer()).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(balances.balance(f.teen().id()).balanceAmount())
                        .isEqualTo(500_00));
        return f;
    }

    private UUID requestPayout(Family f, long amount, String key) throws Exception {
        String body = "{\"parentId\":\"" + f.parent().id() + "\",\"amount\":" + amount + "}";
        String response = fx.send("/payouts", body, f.teen().bearer(), "Idempotency-Key", key)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    private int debits(UUID payoutId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger.ledger_entries WHERE kind = 'PAYOUT' AND ref_id = ?",
                Integer.class,
                payoutId);
    }

    @Test
    void concurrentRequestsLeaveExactlyOneOpenPayout() throws Exception {
        Family f = richFamily();
        String body = "{\"parentId\":\"" + f.parent().id() + "\",\"amount\":10000}";

        List<Integer> statuses =
                runConcurrently(index -> fx.send("/payouts", body, f.teen().bearer(), "Idempotency-Key", "r" + index)
                        .andReturn()
                        .getResponse()
                        .getStatus());

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(THREADS - 1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ledger.payouts WHERE teen_id = ?",
                        Integer.class,
                        f.teen().id()))
                .isEqualTo(1);
    }

    @Test
    void concurrentFulfilmentsDebitTheWalletOnce() throws Exception {
        Family f = richFamily();
        UUID id = requestPayout(f, 300_00, "one");

        List<Integer> statuses = runConcurrently(
                index -> fx.send("/payouts/" + id + "/fulfil", "", f.parent().bearer())
                        .andReturn()
                        .getResponse()
                        .getStatus());

        assertThat(statuses).allMatch(s -> s == 200);
        assertThat(debits(id)).isEqualTo(1);
        assertThat(balances.balance(f.teen().id()).balanceAmount()).isEqualTo(200_00);
    }

    @Test
    void aFulfilmentRacingACancellationHasOneOutcomeAndTheWalletAgrees() throws Exception {
        for (int round = 0; round < 10; round++) {
            Family f = richFamily();
            UUID id = requestPayout(f, 300_00, "race");

            List<Integer> statuses = runConcurrently(index -> index % 2 == 0
                    ? fx.send("/payouts/" + id + "/fulfil", "", f.parent().bearer())
                            .andReturn()
                            .getResponse()
                            .getStatus()
                    : fx.send("/payouts/" + id + "/cancel", "", f.teen().bearer())
                            .andReturn()
                            .getResponse()
                            .getStatus());

            assertThat(statuses).allMatch(s -> s == 200 || s == 409);
            String state = jdbc.queryForObject("SELECT state FROM ledger.payouts WHERE id = ?", String.class, id);
            long approved = balances.balance(f.teen().id()).balanceAmount();
            if (state.equals("FULFILLED")) {
                assertThat(debits(id)).isEqualTo(1);
                assertThat(approved).isEqualTo(200_00);
            } else {
                assertThat(state).isEqualTo("CANCELLED");
                assertThat(debits(id)).isZero();
                assertThat(approved).isEqualTo(500_00);
            }
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
