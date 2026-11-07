package kz.tiyn.platform.idempotency;

import static kz.tiyn.platform.jooq.platform.Tables.IDEMPOTENCY_KEYS;
import static org.jooq.impl.DSL.row;

import java.time.Duration;
import java.time.Instant;
import kz.tiyn.platform.tick.Ticker;
import kz.tiyn.platform.time.Instants;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

@Component
class IdempotencyCleanupTicker implements Ticker {

    private static final int BATCH = 1000;

    private final DSLContext dsl;
    private final IdempotencyProperties properties;

    IdempotencyCleanupTicker(DSLContext dsl, IdempotencyProperties properties) {
        this.dsl = dsl;
        this.properties = properties;
    }

    @Override
    public Duration interval() {
        return Duration.ofHours(1);
    }

    @Override
    public void tick(Instant now) {
        var cutoff = Instants.toUtc(now.minus(properties.ttl()));
        int deleted;
        do {
            deleted = dsl.deleteFrom(IDEMPOTENCY_KEYS)
                    .where(row(IDEMPOTENCY_KEYS.USER_ID, IDEMPOTENCY_KEYS.KEY)
                            .in(dsl.select(IDEMPOTENCY_KEYS.USER_ID, IDEMPOTENCY_KEYS.KEY)
                                    .from(IDEMPOTENCY_KEYS)
                                    .where(IDEMPOTENCY_KEYS.CREATED_AT.lt(cutoff))
                                    .limit(BATCH)))
                    .execute();
        } while (deleted == BATCH);
    }
}
