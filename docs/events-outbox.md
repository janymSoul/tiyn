# Events & the Transactional Outbox

Cross-module events are built on the transactional outbox pattern plus a small in-process dispatcher (`internal/platform/eventbus`). This is what gives us **at-least-once** cross-module delivery (PRD footnote [d]).

## Why an outbox at all

The naive approach — publish to the event bus right after committing the DB transaction — has a fatal gap: the process can crash *between* the commit and the publish, and the event is lost forever. For a ledger credit triggered by task approval, a lost event means a teen who was approved but never paid. Unacceptable.

The outbox closes the gap by making the event part of the transaction:

1. The business change and an `outbox_events` row are written **in the same DB transaction**. They commit atomically — either both or neither.
2. A separate **relay** polls `platform.outbox_events` and delivers undelivered rows to the dispatcher, marking them delivered.
3. If the process crashes at any point, the row is still pending and is delivered again → **at-least-once**, never zero. Duplicates are possible, which is why consumers are idempotent.

## The outbox table

```sql
CREATE TABLE platform.outbox_events (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type      TEXT        NOT NULL,
    aggregate_id    UUID        NOT NULL,
    payload         JSONB       NOT NULL,
    idempotency_key TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_type, idempotency_key)
);
CREATE INDEX outbox_events_pending_idx ON platform.outbox_events (id) WHERE published_at IS NULL;
CREATE INDEX outbox_events_aggregate_pending_idx ON platform.outbox_events (aggregate_id, id) WHERE published_at IS NULL;
```

`published_at IS NULL` means pending. The partial indexes keep the relay's query cheap as the table grows. Delivered rows are retained for audit (SYS-01 spirit) and can be archived or pruned by a separate job, never by the relay. The `UNIQUE (event_type, idempotency_key)` constraint rejects a producer that enqueues the same event twice, so an event that can legitimately repeat (e.g. a task submitted again after a redo) needs the attempt number in its key.

## Writing an event (producer side)

Producers never call the dispatcher directly. Inside their transaction they write an outbox row:

```go
err := store.ExecTx(ctx, func(tx pgx.Tx) error {
    if err := saveTask(ctx, tx, task); err != nil {
        return err
    }
    return outbox.Insert(ctx, tx, outbox.Event{
        Type:           "TaskApprovedEvent",
        AggregateID:    task.ID,
        IdempotencyKey: "REWARD-TASK-" + task.ID.String(),
        Payload:        TaskApprovedEvent{TaskID: task.ID, TeenID: task.TeenID, RewardAmount: task.Reward.Amount, Currency: task.Reward.Currency},
    })
})
```

## The dispatcher (`eventbus.Bus`)

Modules register handlers at startup; the relay publishes through the same object:

```go
bus.Handle("ledger-credit", "TaskApprovedEvent", creditWallet)   // returns an error on a duplicate name
bus.Publish(ctx, "TaskApprovedEvent", msg)
```

`Publish` is synchronous and in-process. For each handler registered for the topic, in registration order, it:

1. runs the handler with the publish context attached to the message, on a copy of the message, recovering panics;
2. retries a failing handler with exponential backoff (`HANDLER_MAX_RETRIES`);
3. after the last retry, writes the message to `platform.dead_letters` (topic, payload, metadata including the handler name, reason) and moves on.

`Publish` returns an error in exactly three cases, and in each the event must stay pending:

- no handler is registered for the topic (`ErrNoHandler`);
- the context was cancelled or timed out (shutdown, delivery timeout) — a failure during shutdown is never dead-lettered;
- the failure could not be recorded (the dead-letter write failed).

It returns nil when every handler succeeded or its failure was durably recorded. There are no goroutines, no router to start, and no startup-ordering requirement: handlers must be registered before events can be delivered, and the relay refuses to deliver until they are, so a deploy that registers a handler late loses nothing.

Handlers keep the Watermill `message.Message` type and the `func(*message.Message) error` signature. When a module is extracted or a durable broker is needed, the relay's target changes to a broker publisher and handlers run under a Watermill router; handler code does not change.

## The relay (publisher side)

A `Ticker` (see `docs/modules.md`, "Time-driven work"). The relay runs as an in-process loop (about 1 s) in the API pods, not from the CronJob, because the CronJob's one-minute granularity would delay event delivery.

```go
func (r *Relay) Tick(ctx context.Context, _ time.Time) error {
    ids, err := r.deliverableIDs(ctx)
    ...
    for _, id := range ids {
        if err := r.deliver(ctx, id); err != nil {
            r.logDeliveryFailure(id, err)
        }
    }
    return nil
}

func (r *Relay) deliver(ctx context.Context, id int64) error {
    publishErr := r.store.ExecTx(ctx, func(tx pgx.Tx) error {
        event, err := lockForDelivery(ctx, tx, id)
        if errors.Is(err, pgx.ErrNoRows) {
            return nil
        }
        ...
        deliveryCtx, cancel := context.WithTimeout(ctx, r.config.DeliveryTimeout)
        defer cancel()
        if err := r.publisher.Publish(deliveryCtx, event.eventType, toMessage(event)); err != nil {
            return err
        }
        _, err = tx.Exec(ctx, `UPDATE platform.outbox_events SET published_at = now() WHERE id = $1`, id)
        return err
    })
    if publishErr == nil || !countsAsFailure(ctx, publishErr) {
        return publishErr
    }
    return errors.Join(publishErr, r.recordFailure(ctx, id))
}
```

Properties, each covered by a test in `internal/platform/outbox`:

- **One event, one short transaction.** The row is locked with `FOR UPDATE SKIP LOCKED`, delivered, and marked published in the same transaction. A relay never waits behind a row another relay holds, and several relays or overlapping ticks never deliver an event twice concurrently.
- **Bounded.** `RELAY_DELIVERY_TIMEOUT` (default 30 s) cuts off a hung handler, so a lock and a connection are never held forever.
- **Backoff, never parking.** A failed delivery (dead-letter write failed, timeout, database error) is recorded in a *separate* transaction: `attempts` is incremented and `next_attempt_at` is pushed out by `2^attempts` seconds, capped at `RELAY_MAX_BACKOFF` (default 5 min). The event is retried forever with that backoff and each failure is logged at error level. Nothing is parked automatically: for a ledger, a visibly stuck event is safer than a silently abandoned one.
- **Not counted as failures:** cancellation of the relay's own context (shutdown) and `ErrNoHandler` (a deploy in progress). Neither increments `attempts` nor backs off; a missing handler is logged at warn level and the event is delivered as soon as a handler exists.
- **Per-aggregate order.** An event is delivered only when no earlier unpublished event of the same aggregate exists. A backed-off event therefore holds back its successors (the next task transition) while other aggregates keep flowing. Order across aggregates is not guaranteed. `created_at` is the transaction start time and is never used for ordering; `id` is assigned at insert, after the aggregate's row lock, so a later transition always gets a higher `id`.
- **Idempotent redelivery.** If the process dies after a handler succeeded but before `published_at` is saved, the event is delivered again. Handlers must be idempotent (below).

### Poll vs. LISTEN/NOTIFY

Start with polling (every ~1s). It is simple, observable, and correct. pgx supports `LISTEN/NOTIFY`, which can cut latency, but adds a wakeup path to get right; treat it as a latency optimization layered on top of the poll, never a replacement for it (NOTIFY is fire-and-forget and can be missed — the poll is the safety net).

## Consumer side — idempotency is mandatory

Because delivery is at-least-once, **every consumer must be idempotent.** The outbox carries an `idempotency_key`; the consumer uses it as the uniqueness key of whatever effect it produces. The dispatcher does not deduplicate for consumers; the consumer's own unique constraint is the guard.

```go
func (h *CreditHandler) Handle(msg *message.Message) error {
    return h.service.CreditTaskReward(msg.Context(), parse(msg))
}
```

Inside `CreditTaskReward`, one transaction inserts the ledger entry with `idempotency_key = REWARD-TASK-{taskID}` (a `UNIQUE` column) and updates the wallet. A redelivered event hits the unique constraint, which the service treats as success, and nothing changes. The teen is credited exactly once despite at-least-once delivery. The unique index on `(ref_id) WHERE kind = 'TASK_REWARD'` is a second guard against crediting one task twice under different keys.

### Consumer failure handling and operations

- **Transient handler failure:** retried by the dispatcher; the event is delivered normally.
- **Poison event (a handler that keeps failing):** after the retries the message is in `platform.dead_letters` and the outbox row counts as delivered, so one poison event never blocks the queue. The dead-letter row is the record of the failure. Operators fix the cause, re-enqueue the payload, and set `redriven_at`.
- **Infrastructure failure (database, timeout):** the event stays pending and is retried with backoff; see above.
- **Head-of-line blocking (accepted trade-off):** the relay delivers one event at a time, and a retrying handler (up to a few seconds of backoff) delays the events behind it. A slow consumer also slows the other consumers of the same event. Acceptable at MVP volume. If it hurts, deliver different aggregates in parallel, or move to per-consumer delivery (a job queue such as River, or a broker).
- **Monitoring to add before production:** alert on the age of the oldest pending outbox row, on the number of rows with `attempts > 0`, and on unredriven dead letters. There is no automatic redrive tool yet.
- **Not a replayable log.** The outbox is the durable producer-side record and published rows are retained, but consumers have no offsets, so replay is manual. If a real replay need appears (new consumer, extraction, incident), move the relay's target to a durable broker such as NATS JetStream or Redpanda. Handlers do not change; events must stay versioned and keyed by aggregate.

## Event catalogue (MVP)

| Event | Producer | Consumers | Effect |
|---|---|---|---|
| `FamilyLinkedEvent` | identity | ledger, notification | seed wallet; welcome push |
| `TaskAssignedEvent` | task | notification | "new task / milestone" → teen |
| `TaskSubmittedEvent` | task | notification | "proof pending" → parent |
| `TaskApprovedEvent` | task | ledger, notification | credit wallet; "reward credited" → teen |
| `TaskRejectedEvent` | task | notification | "rejected" → teen |
| `TaskRedoRequestedEvent` | task | notification | "redo" → teen |
| `TaskCancelledEvent` | task | notification | "cancelled" → teen |
| `TaskExpiredEvent` | task | notification | "deadline passed" → teen |
| `PayoutRequestedEvent` | ledger | notification | high-priority "payout request" → parent |
| `PayoutFulfilledEvent` | ledger | notification | "paid" → teen |
| `PayoutCancelledEvent` | ledger | notification | "request cancelled / declined / expired" → teen |
| `PenaltyAppliedEvent` | ledger | notification | "deduction" → teen |

Event payloads are plain exported structs in the producing module's `events.go`. They are part of the module's **public contract** — changing a payload shape is a contract change; version it (`TaskApprovedEventV2`) rather than silently reshaping.

## Open decisions (flagged, not yet decided)

- **Relay under scale-to-zero.** Decided: Kubernetes, with the relay as an in-process loop in always-running API pods. If the deployment ever scales the API to zero, events would sit pending until the next request; revisit then.
- **Delivery model beyond the MVP.** The synchronous dispatcher is the simplest thing that is correct. Candidates for later, if head-of-line blocking or replay becomes a real need: River (Postgres job queue, per-consumer retries and a dashboard), Watermill's SQL pub/sub, or a broker (NATS JetStream, Redpanda). The `Bus.Handle` / `Bus.Publish` seam is the only code that would change.
