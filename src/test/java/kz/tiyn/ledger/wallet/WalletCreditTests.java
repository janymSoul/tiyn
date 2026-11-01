package kz.tiyn.ledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kz.tiyn.ledger.wallet.error.LedgerError;
import kz.tiyn.ledger.wallet.error.LedgerException;
import kz.tiyn.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class WalletCreditTests {

    private static final int THREADS = 8;

    @Autowired
    WalletService service;

    @Autowired
    JdbcTemplate jdbc;

    private long balanceOf(UUID teenId) {
        return jdbc.queryForObject("SELECT balance_amount FROM ledger.wallets WHERE teen_id = ?", Long.class, teenId);
    }

    private int entriesOf(UUID teenId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger.ledger_entries WHERE teen_id = ?", Integer.class, teenId);
    }

    @Test
    void theFirstCreditCreatesTheWalletAndOneEntry() {
        UUID teen = UUID.randomUUID();
        UUID task = UUID.randomUUID();

        service.creditTaskReward(task, teen, 700, "KZT");

        assertThat(balanceOf(teen)).isEqualTo(700);
        assertThat(entriesOf(teen)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT idempotency_key FROM ledger.ledger_entries WHERE ref_id = ?", String.class, task))
                .isEqualTo("REWARD-TASK-" + task);
    }

    @Test
    void creditingTheSameTaskAgainChangesNothing() {
        UUID teen = UUID.randomUUID();
        UUID task = UUID.randomUUID();

        service.creditTaskReward(task, teen, 700, "KZT");
        service.creditTaskReward(task, teen, 700, "KZT");

        assertThat(balanceOf(teen)).isEqualTo(700);
        assertThat(entriesOf(teen)).isEqualTo(1);
    }

    @Test
    void differentTasksAddUp() {
        UUID teen = UUID.randomUUID();

        service.creditTaskReward(UUID.randomUUID(), teen, 300, "KZT");
        service.creditTaskReward(UUID.randomUUID(), teen, 450, "KZT");

        assertThat(balanceOf(teen)).isEqualTo(750);
        assertThat(entriesOf(teen)).isEqualTo(2);
    }

    @Test
    void invalidCreditsAreRejectedAndLeaveNoTrace() {
        UUID teen = UUID.randomUUID();

        assertThatThrownBy(() -> service.creditTaskReward(UUID.randomUUID(), teen, 0, "KZT"))
                .isInstanceOfSatisfying(
                        LedgerException.class, e -> assertThat(e.error()).isEqualTo(LedgerError.INVALID_AMOUNT));
        assertThatThrownBy(() -> service.creditTaskReward(UUID.randomUUID(), teen, 10, "USD"))
                .isInstanceOfSatisfying(
                        LedgerException.class, e -> assertThat(e.error()).isEqualTo(LedgerError.UNSUPPORTED_CURRENCY));

        assertThat(entriesOf(teen)).isZero();
        assertThat(countWallets(teen)).isZero();
    }

    private int countWallets(UUID teen) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger.wallets WHERE teen_id = ?", Integer.class, teen);
    }

    @Test
    void concurrentCreditsOfTheSameTaskPayOnce() throws Exception {
        UUID teen = UUID.randomUUID();
        UUID task = UUID.randomUUID();

        runConcurrently(index -> {
            service.creditTaskReward(task, teen, 500, "KZT");
            return null;
        });

        assertThat(balanceOf(teen)).isEqualTo(500);
        assertThat(entriesOf(teen)).isEqualTo(1);
    }

    @Test
    void concurrentCreditsOfDifferentTasksForANewTeenLoseNothing() throws Exception {
        UUID teen = UUID.randomUUID();

        runConcurrently(index -> {
            service.creditTaskReward(UUID.randomUUID(), teen, 100, "KZT");
            return null;
        });

        assertThat(balanceOf(teen)).isEqualTo(THREADS * 100L);
        assertThat(entriesOf(teen)).isEqualTo(THREADS);
        assertThat(countWallets(teen)).isEqualTo(1);
    }

    private <T> List<T> runConcurrently(java.util.function.IntFunction<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                int index = i;
                Callable<T> task = () -> {
                    start.await();
                    return work.apply(index);
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
