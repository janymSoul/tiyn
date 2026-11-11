# Events & the Event Publication Registry

Cross-module events are plain Java records published through Spring's `ApplicationEventPublisher` and delivered by Spring Modulith's **event publication registry**. The registry is the transactional outbox: it gives us **at-least-once** cross-module delivery (PRD footnote [d]) without a hand-written relay.

## Why a registry at all

The naive approach — fire a listener right after committing the DB transaction — has a fatal gap: the process can crash *between* the commit and the listener, and the event is lost forever. For a ledger credit triggered by task approval, a lost event means a teen who was approved but never paid. Unacceptable.

The registry closes the gap by making the event part of the transaction:

1. The business change and one `event_publication` row **per interested listener** are written **in the same DB transaction**. They commit atomically — either both or neither.
2. After commit, each `@ApplicationModuleListener` runs asynchronously, in its own new transaction. When it finishes, its row gets a `completion_date`.
3. If the process crashes or a listener throws, the row stays incomplete. With `republish-outstanding-events-on-restart: true` (set in `application.yml`) incomplete rows are redelivered on the next start → **at-least-once**, never zero. Duplicates are possible, which is why consumers are idempotent.

## The table

`event_publication` is created by Flyway (`V6__event_publication.sql`), not by Modulith (`schema-initialization.enabled: false`), so production schema changes stay in migrations. It is Modulith's own DDL for the version in the `pom.xml`; when upgrading Modulith, diff its `schema-postgresql.sql` against ours and add a migration.

```sql
CREATE TABLE event_publication (
    id               UUID NOT NULL PRIMARY KEY,
    listener_id      TEXT NOT NULL,
    event_type       TEXT NOT NULL,
    serialized_event TEXT NOT NULL,
    publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date  TIMESTAMP WITH TIME ZONE
);
```

`completion_date IS NULL` means pending. It sits in the default schema because Modulith addresses it unqualified, and it is excluded from jOOQ code generation. It is owned by no module.

**A publication row exists only per transactional listener.** An event with no `@ApplicationModuleListener` is not stored anywhere. Until `ledger` and `notification` exist, `FamilyLinked` and `FamilyUnlinked` are published and then forgotten. This is correct, but it means a test that checks "the event is durable" needs a real listener registered.

## Publishing an event (producer side)

Aggregates only *record* events as facts (an `EventLog` they own); they never publish. The producing store's `save` writes the aggregate and then publishes the events it recorded, so the write and the publication happen in the service's transaction and a service cannot forget the second half. Nothing calls listeners directly:

```java
@Transactional
public void approveTask(ApproveTask cmd) {
    Task task = tasks.findForUpdate(cmd.taskId());
    task.approve(cmd.parentId(), clock.instant());
    tasks.save(task);   // UPDATE with the version check, then publishes the TaskApproved the task recorded
}
```

`save` does nothing when the aggregate recorded no events (a repeated, no-op command), and it publishes only after the write succeeded: a version conflict throws before any event leaves. A store publishes only the events of its own aggregate; anything spanning aggregates stays in the service.

Events are records in the producing module's **root package**, so other modules may depend on them and nothing else of the producer.

## Consuming an event (listener side)

```java
@ApplicationModuleListener
void on(TaskApproved event) {
    ledger.creditTaskReward(event.taskId(), event.teenId(), event.rewardAmount(), event.currency());
}
```

`@ApplicationModuleListener` is `@Async` + `@Transactional(REQUIRES_NEW)` + `@TransactionalEventListener(AFTER_COMMIT)`. The listener's own work is atomic with marking the publication complete.

### Consumer idempotency is mandatory

Because delivery is at-least-once, **every listener must be idempotent.** Modulith does not deduplicate for consumers; the consumer's own unique constraint is the guard. `creditTaskReward` inserts the ledger entry with `idempotency_key = REWARD-TASK-{taskId}` (a `UNIQUE` column) and updates the wallet in one transaction. A redelivered event hits the unique constraint, which the service treats as success, and nothing changes. The unique index on `(ref_id) WHERE kind = 'TASK_REWARD'` is a second guard against crediting one task twice under different keys. Notification uses `notification.delivered (event_key, recipient_id)` the same way.

Events do not carry an idempotency key. Each consumer derives the key of its own effect from the event's natural identifiers (`taskId`, or `(parentId, teenId, generation)` for link events). An event that can legitimately repeat for one aggregate (a task submitted again after a redo) needs a distinguishing value in its payload, such as the attempt number.

### Order and state

Modulith delivers asynchronously and gives **no ordering guarantee** across listeners or between events of one aggregate. The Go design's per-aggregate ordering guard is deliberately not rebuilt. Two rules replace it:

- **Handlers must be state-based and idempotent,** not dependent on seeing every event in sequence. A listener looks at the current state (is the pair linked now? is the task already credited?), not at "the previous event".
- **Producers publish inside the transaction that locks or updates the aggregate row,** so changes to one aggregate are serialised even though delivery is not. Events that can recur for one aggregate carry a monotonically increasing value (`generation`, attempt number) so consumers can tell a new fact from a replay.

If a flow ever needs strict ordering (it does not today), that is a reason to add it for that flow, not globally.

## Failure handling and operations

- **Transient listener failure:** the exception is logged, the publication stays incomplete. Modulith does **not** retry on its own while the process runs; it retries on restart or when asked.
- **Resubmission ticker:** `EventResubmitTicker` (in `kz.tiyn.platform.tick`) calls `IncompleteEventPublications.resubmitIncompletePublicationsOlderThan(...)` on every tick, with the age taken from `tiyn.events.resubmit-after` (env `EVENTS_RESUBMIT_AFTER`, default `PT1M`). A failed listener is therefore retried within about two minutes instead of at the next deployment. Resubmission can redeliver while a slow first attempt is still running, which is one more reason listeners must be idempotent.
- **Poison event:** a listener that always fails keeps retrying on every resubmission. There is no dead-letter table; the incomplete publication is the record. Alert on it (below) and fix the listener. Because retries re-run the whole listener, it must stay idempotent.
- **Completed publications** accumulate. Purge them on a schedule with `CompletedEventPublications.deletePublicationsOlderThan(...)`, or set `spring.modulith.events.completion-mode` to `delete`/`archive`. Decide against the audit need before production; the ledger is the audit trail, not this table.
- **Monitoring to add before production:** the age of the oldest incomplete publication, the count of incomplete rows per `listener_id`, and listener error rates.
- **Pool budget:** each asynchronous listener holds a connection while it runs, so size `DB_MAX_CONNS` (default 20) for HTTP requests plus the async executor's threads.
- **Shutdown:** a listener interrupted by shutdown stays incomplete and is redelivered after restart. That repeat is harmless because listeners are idempotent.
- **Payload compatibility:** `serialized_event` is JSON. Renaming or removing a record component breaks redelivery of rows written before the deploy. Treat a payload change as a contract change: add fields (nullable), or version the type (`TaskApprovedV2`), and drain old publications first.

## Testing

- Unit-test the domain method that produces the event; it is pure.
- In an `@IntegrationTest`, `@RecordApplicationEvents` captures events in memory (`IdentityFlowTests` does this). That proves what is published, not that it is durable.
- To prove durability and redelivery, use a real listener against Testcontainers Postgres: publish, make the listener fail once, resubmit, and assert the effect happened exactly once. `EventDurabilityTests` does this with a test listener on `TaskAssigned` and `resubmit-after=PT0S`; read the listener's state through methods, not fields, because `@ApplicationModuleListener` makes the bean a CGLIB proxy. The ledger's first test should follow the same shape.
- `@ApplicationModuleTest` with `Scenario` runs one module in isolation and waits for asynchronous effects without sleeping.

## Event catalogue (MVP)

| Event | Producer | Consumers | Effect |
|---|---|---|---|
| `FamilyLinked` | identity | notification (one listener per side) | welcome push to both sides, each in its own publication so one side's failure never re-sends the other's push (the ledger creates wallets lazily on the first credit) |
| `FamilyUnlinked` | identity | task, notification | cancel the teen's open tasks for that parent; tell the teen |
| `TaskAssigned` | task | notification | "new task / milestone" → teen |
| `TaskSubmitted` | task | notification | "proof pending" → parent |
| `TaskApproved` | task | ledger, notification | credit wallet; "reward credited" → teen |
| `TaskRejected` | task | notification | "rejected" → teen |
| `TaskRedoRequested` | task | notification | "redo" → teen |
| `TaskCancelled` | task | notification | "cancelled" → teen |
| `TaskExpired` | task | ledger, notification | debit the task's penalty, if any; "deadline passed" → teen |
| `PayoutRequested` | ledger | notification | high-priority "payout request" → parent |
| `PayoutFulfilled` | ledger | notification | "paid" → teen |
| `PayoutCancelled` | ledger | notification | to the parent when the teen cancelled; otherwise "declined / expired / unlinked" → teen |
| `PenaltyApplied` | ledger | notification | "deduction" → teen |
| `BalanceAdjusted` | ledger | notification | "bonus" or "deduction from your parent" → teen (proposed manual adjustment) |

`FamilyLinked`, `FamilyUnlinked` and all the `Task*` events exist in code. The real listeners today are the two `FamilyUnlinked` handlers in the task module (cancel open tasks, close series), the ledger's `FamilyUnlinked` handler (cancel the open payout) and its `TaskApproved` handler (credit the wallet) and `TaskExpired` handler (debit the penalty); `PayoutRequested`, `PayoutFulfilled`, `PayoutCancelled`, `PenaltyApplied` and `BalanceAdjusted` are consumed by the notification module; the notification module consumes every event in the table (idempotent through `notification.delivered`, claimed before sending). Event payloads are records in the producing module's root package and are part of its **public contract**.

## Before production

- A purge or archive policy for completed publications.
- Alerts on the age of the oldest incomplete publication and on repeated listener failures.
- A way to inspect and re-run a stuck publication by hand (an admin endpoint or a SQL runbook), built with the first real consumer.

## Open decisions

- **Ordering.** Not guaranteed, by design. Revisit only if a concrete flow cannot be made state-based.
- **Beyond the MVP.** If head-of-line effects, replay or a second service appear, externalise events to a broker (`@Externalized` with the Modulith Kafka/AMQP/JMS modules). Producers and listeners stay as they are; the registry keeps the outbox guarantee.
- **Scale-to-zero.** Decided: Kubernetes with always-running API pods. If the API ever scales to zero, pending publications wait until the next start; revisit then.
