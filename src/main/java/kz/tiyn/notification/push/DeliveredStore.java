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

    boolean exists(String eventKey, UUID recipientId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(DELIVERED)
                .where(DELIVERED.EVENT_KEY.eq(eventKey))
                .and(DELIVERED.RECIPIENT_ID.eq(recipientId)));
    }

    void mark(String eventKey, UUID recipientId) {
        dsl.insertInto(DELIVERED)
                .set(DELIVERED.EVENT_KEY, eventKey)
                .set(DELIVERED.RECIPIENT_ID, recipientId)
                .onConflictDoNothing()
                .execute();
    }
}
