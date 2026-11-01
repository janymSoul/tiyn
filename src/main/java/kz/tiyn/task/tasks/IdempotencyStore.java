package kz.tiyn.task.tasks;

import static kz.tiyn.platform.jooq.task.Tables.IDEMPOTENCY_KEYS;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
class IdempotencyStore {

    private final DSLContext dsl;

    IdempotencyStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    boolean claim(String key) {
        return dsl.insertInto(IDEMPOTENCY_KEYS)
                        .set(IDEMPOTENCY_KEYS.KEY, key)
                        .onConflictDoNothing()
                        .execute()
                == 1;
    }
}
