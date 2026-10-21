# Events & the Transactional Outbox

Cross-module events are built on the transactional outbox pattern plus Watermill. This is what gives us **at-least-once** cross-module delivery (PRD footnote [d]).

## Why an outbox at all

The naive approach — publish to the event bus right after committing the DB transaction — has a fatal gap: the process can crash *between* the commit and the publish, and the event is lost forever. For a ledger credit triggered by task approval, a lost event means a teen who was approved but never paid. Unacceptable.

The outbox closes the gap by making the event part of the transaction:

1. The business change and an `outbox_events` row are written **in the same DB transaction**. They commit atomically — either both or neither.
2. A separate **relay** polls `platform.outbox_events` and publishes undelivered rows to the bus, marking them delivered.
3. If the relay crashes mid-publish, the row is still `pending` and gets republished → **at-least-once**, never zero. Duplicates are possible, which is why consumers are idempotent.

## The outbox table

```sql
CREATE TABLE platform.outbox_events (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type    TEXT        NOT NULL,          -- "TaskApprovedEvent"
    aggregate_id  UUID        NOT NULL,          -- for tracing / partitioning
    payload       JSONB       NOT NULL,          -- the serialized event
    idempotency_key TEXT      NOT NULL,          -- carried to the consumer
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,                   -- NULL = pending
    attempts      INT         NOT NULL DEFAULT 0,
    failed_at     TIMESTAMPTZ,                   -- set after max attempts; parked for inspection
    UNIQUE (event_type, idempotency_key)
);
CREATE INDEX outbox_events_pending_idx ON platform.outbox_events (id) WHERE published_at IS NULL AND failed_at IS NULL;
```

The partial index keeps the relay's "find pending" query cheap as the table grows. Delivered rows are retained for audit (SYS-01 spirit) and can be archived/pruned by a separate job, never by the relay.

## Writing an event (producer side)

Producers never call the bus directly. They write an outbox row inside their transaction:

```sql
-- name: InsertOutboxEvent :exec
INSERT INTO platform.outbox_events (event_type, aggregate_id, payload, idempotency_key)
VALUES ($1, $2, $3, $4);
```

A small helper serializes a typed event to the row:

```go
// outbox.From builds the insert params from a typed event payload.
func From(eventType string, aggregateID uuid.UUID, idemKey string, payload any) InsertOutboxEventParams {
    b, _ := json.Marshal(payload) // payloads are plain structs; marshal cannot fail in practice
    return InsertOutboxEventParams{EventType: eventType, AggregateID: aggregateID, Payload: b, IdempotencyKey: idemKey}
}
```

## The relay (publisher side)

A `Ticker` (see `docs/modules.md`, "Time-driven work"). Each tick polls pending rows, publishes each to Watermill, and marks it published. The relay runs as an in-process loop (about 1 s) in the API pods, not from the CronJob, because the CronJob's one-minute granularity would delay event delivery (see `docs/modules.md`). Concurrency is bounded by `FOR UPDATE SKIP LOCKED`, so several relays or overlapping ticks are safe.

```go
func (r *Relay) tick(ctx context.Context) error {
    ids, err := r.store.PendingOutboxIDs(ctx, batchSize) // ORDER BY id LIMIT $1, unlocked read
    if err != nil {
        return err
    }
    for _, id := range ids {
        if err := r.deliver(ctx, id); err != nil {
            r.logger.Error("outbox delivery", "id", id, "err", err)
        }
    }
    return nil
}

func (r *Relay) deliver(ctx context.Context, id int64) error {
    publishErr := r.store.execTx(ctx, func(q *db.Queries) error {
        row, err := q.LockOutboxEventForDelivery(ctx, id) // FOR UPDATE SKIP LOCKED, still pending and not failed
        if errors.Is(err, pgx.ErrNoRows) {
            return nil
        }
        if err != nil {
            return err
        }
        if err := r.publisher.Publish(topicFor(row.EventType), watermillMessage(row)); err != nil {
            return err
        }
        return q.MarkOutboxPublished(ctx, id)
    })
    if publishErr == nil {
        return nil
    }
    return r.recordFailure(ctx, id, publishErr)
}

func (r *Relay) recordFailure(ctx context.Context, id int64, cause error) error {
    return r.store.execTx(ctx, func(q *db.Queries) error {
        return q.RecordOutboxFailure(ctx, id, maxAttempts) // attempts + 1; failed_at = now() once the cap is reached
    })
}
```

Each event is delivered in its own short transaction, so a slow handler holds a lock on one row only, not a batch. A failed delivery rolls that transaction back (the row stays pending), and the failure is then recorded in a *separate* transaction, so the attempts counter survives the rollback. At the cap the row gets `failed_at`, which takes it out of the pending index so it stops being retried and stops blocking later events. A relay-level failure means the bus itself refused the event (no handler registered for its topic, or the bus is closed); handler failures never reach the relay, see "Consumer failure handling".

`FOR UPDATE SKIP LOCKED` lets the relay take a batch without blocking, and makes it safe to scale to multiple relay instances later. Ordering by `id` keeps one aggregate's events in order: the outbox insert is the last step of a command, after the aggregate's row lock, so a later transition always gets a higher `id`. `created_at` is the transaction start time and must not be used for ordering. Order across aggregates is not guaranteed. The `UNIQUE (event_type, idempotency_key)` constraint rejects a producer that enqueues the same event twice, so events that can legitimately repeat (e.g. a task submitted again after a redo) need the attempt number in their key.

### Poll vs. LISTEN/NOTIFY

Start with polling (every ~1s). It is simple, observable, and correct. pgx supports `LISTEN/NOTIFY`, which can cut latency, but adds a wakeup path to get right; treat it as a latency optimization layered on top of the poll, never a replacement for it (NOTIFY is fire-and-forget and can be missed — the poll is the safety net).

## Watermill wiring

Watermill abstracts pub/sub behind `Publisher`/`Subscriber`. In-process today:

```go
pubsub := gochannel.NewGoChannel(gochannel.Config{BlockPublishUntilSubscriberAck: true}, logger)
```

`internal/platform/eventbus` wraps this GoChannel and a Watermill router into a `Bus`. Modules register handlers with `bus.Handle(name, topic, fn)`; the relay publishes through `bus.Publish`. Three properties of GoChannel shape the wrapper, and each was verified in its source:

- **`Publish` blocks until every handler acks, but never returns a handler error.** A nacked message is resent forever, so a failing handler would stall the relay on that event. The bus therefore wraps every handler in router middleware (recover panics, retry with backoff, then dead-letter) so a handler outcome is always an ack.
- **`Publish` returns immediately when no handler is subscribed.** The relay would mark such an event published with nobody having received it. `Bus.Publish` refuses a topic with no registered handler (`ErrNoHandler`), the event stays pending, and it is parked with `failed_at` after the attempts cap. Handlers must be registered before the relay starts.
- **`Router.Run` does not stop when its context is cancelled** if it has no handlers. `Bus.Run` closes the router on cancellation, otherwise graceful shutdown hangs.

GoChannel persists nothing. The outbox row is the durable copy until all handlers have acked: a crash while a handler runs leaves the row pending, and it is redelivered.

Each consuming module subscribes to the topics it cares about at boot and runs handlers. The **handler signature and logic do not change** when we later swap `gochannel` for `googlecloud.NewPublisher/Subscriber` (Google Pub/Sub) during module extraction — only the wiring in `cmd/` changes. That swap-without-rewrite property is the whole reason Watermill is here rather than raw Go channels.

## Consumer side — idempotency is mandatory

Because delivery is at-least-once, **every consumer must be idempotent.** The outbox carries an `idempotency_key`; the consumer uses it as the uniqueness key of whatever effect it produces.

```go
// Ledger module's TaskApprovedEvent handler.
func (h *CreditHandler) Handle(ctx context.Context, e TaskApprovedEvent) error {
    return h.store.execTx(ctx, func(q *db.Queries) error {
        // e.IdempotencyKey == the key the task module minted, e.g. REWARD-TASK-{taskID}.
        if err := q.InsertIdempotencyKey(ctx, e.IdempotencyKey); err != nil {
            if isUniqueViolation(err) {
                return nil // already credited by a prior delivery — ack and move on
            }
            return err
        }
        return q.InsertLedgerCredit(ctx, creditParams(e))
    })
}
```

A redelivered `TaskApprovedEvent` inserts the same key, hits the `UNIQUE` constraint, and no-ops. The teen is credited exactly once despite at-least-once delivery.

### Consumer failure handling

- A handler that returns an error (or panics) is retried inside the bus with exponential backoff, up to `HANDLER_MAX_RETRIES`. A handler that fails once and then succeeds delivers the event normally. Retries re-run the handler, which is safe because every consumer is idempotent. Keep handlers short and deterministic.
- Poison events: when retries are exhausted the message is written to `platform.dead_letters` (topic, payload, metadata, reason) and acked. Dead letters are durable, unlike an in-memory dead-letter topic, and the relay moves on, so one poison event never blocks the queue. The outbox row counts as delivered; the dead-letter row is the record of the failure. Operators fix the cause and redrive by re-enqueueing the payload, then set `redriven_at`.
- Relay-level failures (the bus refused the event) are separate: `attempts` is bumped in its own transaction and `failed_at` is set at the cap. Operators clear `failed_at` to retry.
- Trade-off: because `Publish` blocks on acks, a slow consumer delays the relay for that event, and the relay's per-event transaction holds that one row's lock meanwhile. Keep handler work short; handlers use their own transactions on their own tables, so there is no lock conflict. Acceptable at MVP scale. If it hurts, add a per-consumer delivery table or move to a durable broker (see below).
- **Not a replayable log.** The outbox is the durable producer-side record and published rows are retained, but consumers have no offsets, so replay is manual. If a real replay need appears (new consumer, extraction, incident), move the relay's target to a durable broker such as NATS JetStream or Redpanda via the Watermill swap. Handlers do not change; events must stay versioned and keyed by aggregate.

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
- **Ordering guarantees.** MVP assumes per-aggregate ordering is "good enough" via `created_at`. If a flow needs strict ordering, partition the relay query by `aggregate_id` and document it.
