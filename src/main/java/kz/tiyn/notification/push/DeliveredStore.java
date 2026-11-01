package kz.tiyn.notification.push;

import static kz.tiyn.platform.jooq.notification.Tables.DELIVERED;

import java.util.UUID;
import org.jooq.DSLContext;
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

    void release(String eventKey, UUID recipientId) {
        dsl.deleteFrom(DELIVERED)
                .where(DELIVERED.EVENT_KEY.eq(eventKey))
                .and(DELIVERED.RECIPIENT_ID.eq(recipientId))
                .execute();
    }
}
