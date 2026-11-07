package kz.tiyn.platform.idempotency;

import static kz.tiyn.platform.jooq.platform.Tables.IDEMPOTENCY_KEYS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class IdempotencyCleanupTickerTests {

    @Autowired
    IdempotencyKeys keys;

    @Autowired
    IdempotencyCleanupTicker ticker;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    @Test
    void deletesOnlyKeysOlderThanTheTtl() {
        UUID user = UUID.randomUUID();
        claim(user, "fresh");
        claim(user, "stale");

        ticker.tick(Instant.now().plusSeconds(23 * 3600));
        assertThat(count(user)).isEqualTo(2);

        ticker.tick(Instant.now().plusSeconds(25 * 3600));
        assertThat(count(user)).isZero();
    }

    private void claim(UUID user, String key) {
        tx.executeWithoutResult(status ->
                keys.claimOrFindPrevious(user, new IdempotencyContext(key), "test.op", UUID.randomUUID(), "request"));
    }

    private int count(UUID user) {
        return dsl.fetchCount(IDEMPOTENCY_KEYS, IDEMPOTENCY_KEYS.USER_ID.eq(user));
    }
}
