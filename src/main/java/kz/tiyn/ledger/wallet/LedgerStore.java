package kz.tiyn.ledger.wallet;

import static kz.tiyn.platform.jooq.ledger.Tables.LEDGER_ENTRIES;

import java.util.Optional;
import java.util.UUID;
import kz.tiyn.platform.jooq.ledger.tables.records.LedgerEntriesRecord;
import kz.tiyn.platform.paging.Cursor;
import kz.tiyn.platform.paging.Page;
import kz.tiyn.platform.paging.PageRequest;
import kz.tiyn.platform.time.Instants;
import org.jooq.DSLContext;
import org.jooq.Result;
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
                .fetchOptional(LedgerStore::toEntry);
    }

    Page<EntryView> pageForTeen(UUID teenId, String filterHash, PageRequest request) {
        var ordered = dsl.selectFrom(LEDGER_ENTRIES)
                .where(LEDGER_ENTRIES.TEEN_ID.eq(teenId))
                .orderBy(LEDGER_ENTRIES.CREATED_AT.desc(), LEDGER_ENTRIES.ID.desc());
        Optional<Cursor> after = request.after(filterHash);
        Result<LedgerEntriesRecord> fetched = after.isPresent()
                ? ordered.seek(
                                Instants.toUtc(after.get().sortKey()),
                                after.get().id())
                        .limit(request.fetchSize())
                        .fetch()
                : ordered.limit(request.fetchSize()).fetch();
        return Page.of(fetched, request, r -> new Cursor(Instants.fromUtc(r.getCreatedAt()), r.getId(), filterHash))
                .map(r -> new EntryView(toEntry(r), Instants.fromUtc(r.getCreatedAt())));
    }

    private static LedgerEntry toEntry(LedgerEntriesRecord r) {
        return new LedgerEntry(
                r.getId(),
                r.getTeenId(),
                r.getCurrency(),
                EntryKind.valueOf(r.getKind()),
                r.getBalanceDelta(),
                r.getLockedDelta(),
                r.getRefId(),
                r.getIdempotencyKey());
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
                .execute();
    }
}
