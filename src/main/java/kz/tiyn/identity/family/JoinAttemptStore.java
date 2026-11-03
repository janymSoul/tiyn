package kz.tiyn.identity.family;

import static kz.tiyn.platform.jooq.identity.Tables.JOIN_ATTEMPTS;

import java.time.Instant;
import java.util.UUID;
import kz.tiyn.platform.time.Instants;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
class JoinAttemptStore {

    private final DSLContext dsl;

    JoinAttemptStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    JoinAttempts lockOrCreate(UUID teenId, Instant now) {
        dsl.insertInto(JOIN_ATTEMPTS)
                .set(JOIN_ATTEMPTS.TEEN_ID, teenId)
                .set(JOIN_ATTEMPTS.ATTEMPTS, 0)
                .set(JOIN_ATTEMPTS.WINDOW_STARTED_AT, Instants.toUtc(now))
                .onConflictDoNothing()
                .execute();
        return dsl.selectFrom(JOIN_ATTEMPTS)
                .where(JOIN_ATTEMPTS.TEEN_ID.eq(teenId))
                .forUpdate()
                .fetchOne(r ->
                        JoinAttempts.restore(r.getTeenId(), r.getAttempts(), Instants.fromUtc(r.getWindowStartedAt())));
    }

    void save(JoinAttempts joinAttempts) {
        dsl.update(JOIN_ATTEMPTS)
                .set(JOIN_ATTEMPTS.ATTEMPTS, joinAttempts.attempts())
                .set(JOIN_ATTEMPTS.WINDOW_STARTED_AT, Instants.toUtc(joinAttempts.windowStartedAt()))
                .where(JOIN_ATTEMPTS.TEEN_ID.eq(joinAttempts.teenId()))
                .execute();
    }

    void clear(UUID teenId) {
        dsl.deleteFrom(JOIN_ATTEMPTS).where(JOIN_ATTEMPTS.TEEN_ID.eq(teenId)).execute();
    }
}
