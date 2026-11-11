package kz.tiyn.ledger.wallet;

import static kz.tiyn.platform.jooq.ledger.Tables.LEDGER_ENTRIES;

import java.util.Optional;
import java.util.UUID;
import kz.tiyn.platform.time.Instants;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
class LedgerStore {

    private final DSLContext dsl;

    LedgerStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    boolean exists(String idempotencyKey) {
        return dsl.fetchExists(
                dsl.selectOne().from(LEDGER_ENTRIES).where(LEDGER_ENTRIES.IDEMPOTENCY_KEY.eq(idempotencyKey)));
    }

    Optional<LedgerEntry> findByKey(String idempotencyKey) {
        return dsl.selectFrom(LEDGER_ENTRIES)
                .where(LEDGER_ENTRIES.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOptional(r -> new LedgerEntry(
                        r.getId(),
                        r.getTeenId(),
                        r.getCurrency(),
                        EntryKind.valueOf(r.getKind()),
                        r.getBalanceDelta(),
                        r.getLockedDelta(),
                        r.getRefId(),
                        r.getIdempotencyKey()));
    }

    void append(LedgerEntry entry) {
        dsl.insertInto(LEDGER_ENTRIES)
                .set(LEDGER_ENTRIES.ID, entry.id())
                .set(LEDGER_ENTRIES.TEEN_ID, entry.teenId())
                .set(LEDGER_ENTRIES.CURRENCY, entry.currency())
                .set(LEDGER_ENTRIES.KIND, entry.kind().name())
                .set(LEDGER_ENTRIES.BALANCE_DELTA, entry.balanceDelta())
                .set(LEDGER_ENTRIES.LOCKED_DELTA, entry.lockedDelta())
                .set(LEDGER_ENTRIES.REF_ID, entry.refId())
                .set(LEDGER_ENTRIES.IDEMPOTENCY_KEY, entry.idempotencyKey())
                .set(LEDGER_ENTRIES.NOTE, entry.note())
                .set(LEDGER_ENTRIES.ACTOR_ID, entry.actorId())
                .execute();
    }

    Optional<AdjustmentView> findAdjustment(UUID entryId) {
        return dsl.selectFrom(LEDGER_ENTRIES)
                .where(LEDGER_ENTRIES.ID.eq(entryId))
                .fetchOptional(r -> new AdjustmentView(
                        r.getId(),
                        r.getTeenId(),
                        r.getActorId(),
                        EntryKind.valueOf(r.getKind()),
                        r.getBalanceDelta(),
                        r.getCurrency(),
                        r.getNote(),
                        Instants.fromUtc(r.getCreatedAt())));
    }
}
