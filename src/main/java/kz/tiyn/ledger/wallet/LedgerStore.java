package kz.tiyn.ledger.wallet;

import static kz.tiyn.platform.jooq.ledger.Tables.LEDGER_ENTRIES;

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
