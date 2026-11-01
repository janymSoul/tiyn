package kz.tiyn.ledger.wallet;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kz.tiyn.ledger.PayoutCancelled;
import kz.tiyn.ledger.PayoutFulfilled;
import kz.tiyn.ledger.PayoutRequested;
import kz.tiyn.ledger.wallet.error.LedgerError;
import kz.tiyn.ledger.wallet.error.LedgerException;
import kz.tiyn.platform.event.EventLog;

final class Payout {

    private final UUID id;
    private final UUID teenId;
    private final String currency;
    private final UUID parentId;
    private final long amount;
    private final String idempotencyKey;
    private final Instant requestedAt;
    private PayoutState state;
    private Instant resolvedAt;
    private UUID resolvedBy;
    private CancelReason cancelReason;
    private final EventLog events = new EventLog();

    private Payout(
            UUID id,
            UUID teenId,
            String currency,
            UUID parentId,
            long amount,
            String idempotencyKey,
            Instant requestedAt,
            PayoutState state,
            Instant resolvedAt,
            UUID resolvedBy,
            CancelReason cancelReason) {
        this.id = id;
        this.teenId = teenId;
        this.currency = currency;
        this.parentId = parentId;
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
        this.requestedAt = requestedAt;
        this.state = state;
        this.resolvedAt = resolvedAt;
        this.resolvedBy = resolvedBy;
        this.cancelReason = cancelReason;
    }

    static Payout request(
            UUID id,
            UUID teenId,
            UUID parentId,
            long amount,
            String currency,
            String idempotencyKey,
            long minAmount,
            Instant now) {
        if (amount <= 0) {
            throw new LedgerException(LedgerError.INVALID_AMOUNT);
        }
        if (amount < minAmount) {
            throw new LedgerException(LedgerError.AMOUNT_TOO_SMALL);
        }
        Payout payout = new Payout(
                id, teenId, currency, parentId, amount, idempotencyKey, now, PayoutState.REQUESTED, null, null, null);
        payout.events.record(new PayoutRequested(id, teenId, parentId, amount, currency));
        return payout;
    }

    static Payout restore(
            UUID id,
            UUID teenId,
            String currency,
            UUID parentId,
            long amount,
            String idempotencyKey,
            Instant requestedAt,
            PayoutState state,
            Instant resolvedAt,
            UUID resolvedBy,
            CancelReason cancelReason) {
        return new Payout(
                id,
                teenId,
                currency,
                parentId,
                amount,
                idempotencyKey,
                requestedAt,
                state,
                resolvedAt,
                resolvedBy,
                cancelReason);
    }

    void fulfil(UUID by, Instant now) {
        if (!parentId.equals(by)) {
            throw new LedgerException(LedgerError.FORBIDDEN);
        }
        if (state == PayoutState.FULFILLED) {
            return;
        }
        if (state != PayoutState.REQUESTED) {
            throw new LedgerException(LedgerError.INVALID_TRANSITION);
        }
        state = PayoutState.FULFILLED;
        resolvedAt = now;
        resolvedBy = by;
        events.record(new PayoutFulfilled(id, teenId, parentId, amount, currency));
    }

    void expire(Instant cutoff, Instant now) {
        if (state != PayoutState.REQUESTED || requestedAt.isAfter(cutoff)) {
            return;
        }
        cancel(CancelReason.EXPIRED, null, now);
    }

    void cancelForUnlink(Instant now) {
        if (state != PayoutState.REQUESTED) {
            return;
        }
        cancel(CancelReason.UNLINKED, null, now);
    }

    void cancelByTeen(UUID by, Instant now) {
        if (!teenId.equals(by)) {
            throw new LedgerException(LedgerError.FORBIDDEN);
        }
        cancel(CancelReason.TEEN_CANCELLED, by, now);
    }

    void decline(UUID by, Instant now) {
        if (!parentId.equals(by)) {
            throw new LedgerException(LedgerError.FORBIDDEN);
        }
        cancel(CancelReason.DECLINED, by, now);
    }

    private void cancel(CancelReason reason, UUID by, Instant now) {
        if (state == PayoutState.CANCELLED && cancelReason == reason) {
            return;
        }
        if (state != PayoutState.REQUESTED) {
            throw new LedgerException(LedgerError.INVALID_TRANSITION);
        }
        state = PayoutState.CANCELLED;
        cancelReason = reason;
        resolvedAt = now;
        resolvedBy = by;
        events.record(new PayoutCancelled(id, teenId, parentId, reason.name()));
    }

    List<Object> pullEvents() {
        return events.drain();
    }

    UUID id() {
        return id;
    }

    UUID teenId() {
        return teenId;
    }

    String currency() {
        return currency;
    }

    UUID parentId() {
        return parentId;
    }

    long amount() {
        return amount;
    }

    String idempotencyKey() {
        return idempotencyKey;
    }

    Instant requestedAt() {
        return requestedAt;
    }

    PayoutState state() {
        return state;
    }

    Instant resolvedAt() {
        return resolvedAt;
    }

    UUID resolvedBy() {
        return resolvedBy;
    }

    CancelReason cancelReason() {
        return cancelReason;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Payout payout && id.equals(payout.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
