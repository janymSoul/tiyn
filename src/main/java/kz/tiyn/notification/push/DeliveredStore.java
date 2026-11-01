package kz.tiyn.notification.push;

import static kz.tiyn.platform.jooq.notification.Tables.DELIVERED;

import java.time.Instant;
import java.util.UUID;
import kz.tiyn.platform.time.Instants;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
class DeliveredStore {

    private final DSLContext dsl;

    DeliveredStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    boolean claim(String eventKey, UUID recipientId) {
        return dsl.insertInto(DELIVERED)
                        .set(DELIVERED.EVENT_KEY, eventKey)
                        .set(DELIVERED.RECIPIENT_ID, recipientId)
                        .onConflictDoNothing()
                        .execute()
                == 1;
    }

    int deleteOlderThan(Instant cutoff, int limit) {
        return dsl.deleteFrom(DELIVERED)
                .where(DSL.row(DELIVERED.EVENT_KEY, DELIVERED.RECIPIENT_ID)
                        .in(dsl.select(DELIVERED.EVENT_KEY, DELIVERED.RECIPIENT_ID)
                                .from(DELIVERED)
                                .where(DELIVERED.DELIVERED_AT.lt(Instants.toUtc(cutoff)))
                                .limit(limit)))
                .execute();
    }

    void release(String eventKey, UUID recipientId) {
        dsl.deleteFrom(DELIVERED)
                .where(DELIVERED.EVENT_KEY.eq(eventKey))
                .and(DELIVERED.RECIPIENT_ID.eq(recipientId))
                .execute();
    }
}
