package kz.tiyn.platform.idempotency;

import static kz.tiyn.platform.jooq.platform.Tables.IDEMPOTENCY_KEYS;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kz.tiyn.platform.time.Instants;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

@Component
public class OncePerWindow {

    private static final String KEY_PREFIX = "window:";

    private final DSLContext dsl;
    private final Clock clock;

    OncePerWindow(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    public boolean tryAcquire(UUID userId, String operation, Duration window) {
        Instant now = clock.instant();
        int changed = dsl.insertInto(IDEMPOTENCY_KEYS)
                .set(IDEMPOTENCY_KEYS.USER_ID, userId)
                .set(IDEMPOTENCY_KEYS.KEY, KEY_PREFIX + operation)
                .set(IDEMPOTENCY_KEYS.OPERATION, operation)
                .set(IDEMPOTENCY_KEYS.REQUEST_HASH, "")
                .set(IDEMPOTENCY_KEYS.CREATED_AT, Instants.toUtc(now))
                .onConflict(IDEMPOTENCY_KEYS.USER_ID, IDEMPOTENCY_KEYS.KEY)
                .doUpdate()
                .set(IDEMPOTENCY_KEYS.CREATED_AT, Instants.toUtc(now))
                .where(IDEMPOTENCY_KEYS.CREATED_AT.le(Instants.toUtc(now.minus(window))))
                .execute();
        return changed == 1;
    }
}
