# Events & the Transactional Outbox

This is the piece with no Spring Modulith equivalent out of the box — in Spring you'd get the event-publication registry for free; here we build it from the transactional outbox pattern plus Watermill. It is what gives us **at-least-once** cross-module delivery (PRD footnote [d]).

## Why an outbox at all

The naive approach — publish to the event bus right after committing the DB transaction — has a fatal gap: the process can crash *between* the commit and the publish, and the event is lost forever. For a ledger credit triggered by task approval, a lost event means a teen who was approved but never paid. Unacceptable.

The outbox closes the gap by making the event part of the transaction:

1. The business change and an `outbox_events` row are written **in the same DB transaction**. They commit atomically — either both or neither.
2. A separate **relay** polls `outbox_events` and publishes undelivered rows to the bus, marking them delivered.
3. If the relay crashes mid-publish, the row is still `pending` and gets republished → **at-least-once**, never zero. Duplicates are possible, which is why consumers are idempotent.

## The outbox table

```sql
CREATE TABLE outbox_events (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type    TEXT        NOT NULL,          -- "TaskApprovedEvent"
    aggregate_id  UUID        NOT NULL,          -- for tracing / partitioning
    payload       JSONB       NOT NULL,          -- the serialized event
    idempotency_key TEXT      NOT NULL,          -- carried to the consumer
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,                   -- NULL = pending
    attempts      INT         NOT NULL DEFAULT 0
);
CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE published_at IS NULL;
```

The partial index keeps the relay's "find pending" query cheap as the table grows. Delivered rows are retained for audit (SYS-01 spirit) and can be archived/pruned by a separate job, never by the relay.

## Writing an event (producer side)

Producers never call the bus directly. They write an outbox row inside their transaction:

```sql
-- name: InsertOutboxEvent :exec
INSERT INTO outbox_events (event_type, aggregate_id, payload, idempotency_key)
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

A single goroutine started at boot. It polls pending rows, publishes each to Watermill, and marks it published. One relay instance only — concurrency is bounded by `FOR UPDATE SKIP LOCKED` so it is safe to run more than one if needed later.

```go
func (r *Relay) tick(ctx context.Context) error {
    return r.store.execTx(ctx, func(q *db.Queries) error {
        rows, err := q.LockPendingOutbox(ctx, batchSize) // SELECT ... WHERE published_at IS NULL
        if err != nil {                                   // ORDER BY created_at LIMIT $1 FOR UPDATE SKIP LOCKED
            return err
        }
        for _, row := range rows {
            msg := watermillMessage(row) // Watermill message; UUID = row.ID for consumer-side dedupe
            if err := r.publisher.Publish(topicFor(row.EventType), msg); err != nil {
                // leave it pending; bump attempts; next tick retries
                _ = q.BumpOutboxAttempt(ctx, row.ID)
                continue
            }
            if err := q.MarkOutboxPublished(ctx, row.ID); err != nil {
                return err // tx rolls back; row stays pending; republished next tick (at-least-once)
            }
        }
        return nil
    })
}
```

`FOR UPDATE SKIP LOCKED` lets the relay take a batch without blocking, and makes it safe to scale to multiple relay instances later. Ordering by `created_at` gives per-aggregate ordering in practice; if strict per-aggregate ordering is ever required, partition the query by `aggregate_id`.

### Poll vs. LISTEN/NOTIFY

Start with polling (every ~1s). It is simple, observable, and correct. pgx supports `LISTEN/NOTIFY`, which can cut latency, but adds a wakeup path to get right; treat it as a latency optimization layered on top of the poll, never a replacement for it (NOTIFY is fire-and-forget and can be missed — the poll is the safety net).

## Watermill wiring

Watermill abstracts pub/sub behind `Publisher`/`Subscriber`. In-process today:

```go
pubsub := gochannel.NewGoChannel(gochannel.Config{}, watermillLogger)
// publisher := pubsub  (the relay publishes here)
// subscriber := pubsub (modules subscribe here)
```

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

- Handler returns an error → the message is nacked → Watermill redelivers (per backend retry config). Keep handlers short and deterministic.
- Poison messages (always fail) must not block the topic forever. Configure a retry middleware with a cap, then route to a dead-letter topic (`<topic>.DLQ`) for inspection. (This mirrors the user's RabbitMQ `*.DLQ` convention.)

## Event catalogue (MVP)

| Event | Producer | Consumers | Effect |
|---|---|---|---|
| `FamilyLinkedEvent` | identity | ledger, notification | seed wallet; welcome push |
| `TaskSubmittedEvent` | task | notification | "proof pending" → parent |
| `TaskApprovedEvent` | task | ledger, notification | credit wallet; "reward credited" → teen |
| `TaskRejectedEvent` | task | notification | "rejected" → teen |
| `TaskRedoRequestedEvent` | task | notification | "redo" → teen |
| `PayoutRequestedEvent` | ledger | notification | high-priority "payout request" → parent |
| `PayoutFulfilledEvent` | ledger | notification | "paid" → teen |
| `PenaltyAppliedEvent` | ledger | notification | "deduction" → teen |

Event payloads are plain exported structs in the producing module's `events.go`. They are part of the module's **public contract** — changing a payload shape is a contract change; version it (`TaskApprovedEventV2`) rather than silently reshaping.

## Open decisions (flagged, not yet decided)

- **Relay lifecycle under Cloud Run scale-to-zero.** If the API instance scales to zero, the in-process relay stops and events sit pending until the next request wakes an instance. Options: (a) min-instances=1 on the API service, (b) a separate always-on relay worker, (c) a Cloud Scheduler → endpoint that drains the outbox. Decide before production; for local/dev the in-process goroutine is fine.
- **Ordering guarantees.** MVP assumes per-aggregate ordering is "good enough" via `created_at`. If a flow needs strict ordering, partition the relay query by `aggregate_id` and document it.
