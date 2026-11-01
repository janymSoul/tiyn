package kz.tiyn.ledger.wallet;

import static kz.tiyn.platform.jooq.ledger.Tables.PAYOUTS;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kz.tiyn.platform.jooq.ledger.tables.records.PayoutsRecord;
import kz.tiyn.platform.time.Instants;
import kz.tiyn.platform.tx.ConflictException;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
class PayoutStore {

    static final int LIST_LIMIT = 100;

    private final DSLContext dsl;
    private final ApplicationEventPublisher events;

    PayoutStore(DSLContext dsl, ApplicationEventPublisher events) {
        this.dsl = dsl;
        this.events = events;
    }

    void insert(Payout payout) {
        try {
            dsl.insertInto(PAYOUTS)
                    .set(PAYOUTS.ID, payout.id())
                    .set(PAYOUTS.TEEN_ID, payout.teenId())
                    .set(PAYOUTS.CURRENCY, payout.currency())
                    .set(PAYOUTS.PARENT_ID, payout.parentId())
                    .set(PAYOUTS.AMOUNT, payout.amount())
                    .set(PAYOUTS.STATE, payout.state().name())
                    .set(PAYOUTS.IDEMPOTENCY_KEY, payout.idempotencyKey())
                    .set(PAYOUTS.REQUESTED_AT, Instants.toUtc(payout.requestedAt()))
                    .execute();
        } catch (DuplicateKeyException e) {
            throw new ConflictException(e);
        }
        payout.pullEvents().forEach(events::publishEvent);
    }

    void save(Payout payout) {
        dsl.update(PAYOUTS)
                .set(PAYOUTS.STATE, payout.state().name())
                .set(PAYOUTS.RESOLVED_AT, Instants.toUtc(payout.resolvedAt()))
                .set(PAYOUTS.RESOLVED_BY, payout.resolvedBy())
                .set(
                        PAYOUTS.CANCEL_REASON,
                        payout.cancelReason() == null
                                ? null
                                : payout.cancelReason().name())
                .where(PAYOUTS.ID.eq(payout.id()))
                .execute();
        payout.pullEvents().forEach(events::publishEvent);
    }

    Optional<Payout> find(UUID id) {
        return dsl.selectFrom(PAYOUTS).where(PAYOUTS.ID.eq(id)).fetchOptional().map(PayoutStore::toPayout);
    }

    Optional<Payout> findForUpdate(UUID id) {
        return dsl.selectFrom(PAYOUTS)
                .where(PAYOUTS.ID.eq(id))
                .forUpdate()
                .fetchOptional()
                .map(PayoutStore::toPayout);
    }

    Optional<Payout> findByKey(String idempotencyKey) {
        return dsl.selectFrom(PAYOUTS)
                .where(PAYOUTS.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOptional()
                .map(PayoutStore::toPayout);
    }

    List<UUID> findStaleIds(Instant cutoff, int limit) {
        return dsl.select(PAYOUTS.ID)
                .from(PAYOUTS)
                .where(PAYOUTS.STATE.eq(PayoutState.REQUESTED.name()))
                .and(PAYOUTS.REQUESTED_AT.le(Instants.toUtc(cutoff)))
                .orderBy(PAYOUTS.REQUESTED_AT)
                .limit(limit)
                .fetch(PAYOUTS.ID);
    }

    Optional<Payout> findOpen(UUID teenId, UUID parentId) {
        return dsl.selectFrom(PAYOUTS)
                .where(PAYOUTS.TEEN_ID.eq(teenId))
                .and(PAYOUTS.PARENT_ID.eq(parentId))
                .and(PAYOUTS.STATE.eq(PayoutState.REQUESTED.name()))
                .fetchOptional()
                .map(PayoutStore::toPayout);
    }

    boolean hasOpen(UUID teenId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(PAYOUTS)
                .where(PAYOUTS.TEEN_ID.eq(teenId).and(PAYOUTS.STATE.eq(PayoutState.REQUESTED.name()))));
    }

    long openAmount(UUID teenId, String currency) {
        return dsl.select(DSL.coalesce(DSL.sum(PAYOUTS.AMOUNT), java.math.BigDecimal.ZERO))
                .from(PAYOUTS)
                .where(PAYOUTS.TEEN_ID
                        .eq(teenId)
                        .and(PAYOUTS.CURRENCY.eq(currency))
                        .and(PAYOUTS.STATE.eq(PayoutState.REQUESTED.name())))
                .fetchSingle()
                .value1()
                .longValueExact();
    }

    List<Payout> listForTeen(UUID teenId, PayoutState state) {
        return list(PAYOUTS.TEEN_ID.eq(teenId), state);
    }

    List<Payout> listForParent(UUID parentId, PayoutState state) {
        return list(PAYOUTS.PARENT_ID.eq(parentId), state);
    }

    private List<Payout> list(Condition owner, PayoutState state) {
        Condition where = state == null ? owner : owner.and(PAYOUTS.STATE.eq(state.name()));
        return dsl.selectFrom(PAYOUTS)
                .where(where)
                .orderBy(PAYOUTS.REQUESTED_AT.desc())
                .limit(LIST_LIMIT)
                .fetch()
                .map(PayoutStore::toPayout);
    }

    private static Payout toPayout(PayoutsRecord r) {
        return Payout.restore(
                r.getId(),
                r.getTeenId(),
                r.getCurrency(),
                r.getParentId(),
                r.getAmount(),
                r.getIdempotencyKey(),
                Instants.fromUtc(r.getRequestedAt()),
                PayoutState.valueOf(r.getState()),
                Instants.fromUtc(r.getResolvedAt()),
                r.getResolvedBy(),
                r.getCancelReason() == null ? null : CancelReason.valueOf(r.getCancelReason()));
    }
}
