# Transactions, Locking & Idempotency

This is the most important document for correctness. The ledger is money (virtual, but disputes between parent and teen are real). The rules here are not negotiable without an architecture discussion.

## The one rule

**The transaction boundary lives in the service layer, and nowhere else.** A service method opens exactly one transaction, does all its work inside it, and commits or rolls back. Domain code is pure and opens nothing. Repositories (`store/`) accept a transaction-scoped querier and open nothing. HTTP handlers open nothing.

There is no transaction propagation and no nested transactions. That is deliberate: there is exactly one place a transaction can begin or end. Keep it that way.

## The `execTx` closure

Each module's `store` package provides a transaction runner. The transaction boundary is the closure's scope; everything inside is atomic.

```go
// Store wraps the pool and exposes execTx + the sqlc Queries.
type Store struct {
    pool *pgxpool.Pool
    *db.Queries // non-transactional queries (reads outside a tx)
}

// execTx runs fn inside a single transaction. On any error the whole thing
// rolls back; otherwise it commits. fn must use ONLY the provided *db.Queries.
func (s *Store) execTx(ctx context.Context, fn func(*db.Queries) error) error {
    tx, err := s.pool.Begin(ctx)
    if err != nil {
        return fmt.Errorf("begin tx: %w", err)
    }
    if err := fn(db.New(tx)); err != nil {
        _ = tx.Rollback(ctx) // rollback-after-commit is a harmless no-op; ignore its error
        return err
    }
    return tx.Commit(ctx)
}
```

`db.New(tx)` is sqlc's transaction-scoped querier. The closure receives it and must not touch `s.Queries` (the pool-scoped one) inside the transaction — doing so runs outside the tx and breaks atomicity.

### Never nest `execTx`

Calling `execTx` inside `execTx` grabs a second pooled connection and can self-deadlock (the outer holds a lock the inner waits on). Rule: **services open transactions; domain and store never do.** If one service must compose another's work in the same transaction, the caller passes the already-open `*db.Queries` down — it does not open a new one. In practice, cross-service composition should be async (events), so this rarely arises.

## The canonical flow: approve a task

This is the shape every state-changing command follows — guard the FSM, persist the new state, apply the financial effect, and emit the event, all atomically.

```go
func (s *Service) ApproveTask(ctx context.Context, cmd ApproveTaskCmd) error {
    return s.store.execTx(ctx, func(q *db.Queries) error {
        // 1. Idempotency guard. The UNIQUE constraint does the work; a duplicate
        //    command (retry, double-tap) is a no-op success, not an error.
        if err := q.InsertIdempotencyKey(ctx, cmd.IdempotencyKey); err != nil {
            if isUniqueViolation(err) {
                return nil
            }
            return err
        }

        // 2. Load + lock the aggregate.
        row, err := q.GetTaskForUpdate(ctx, cmd.TaskID) // SELECT ... FOR UPDATE
        if err != nil {
            return err
        }
        task := domain.Rehydrate(row)

        // 3. Pure FSM transition. Validates the guard; no I/O.
        if err := task.Approve(cmd.ParentID); err != nil {
            return err // ErrInvalidTransition if not SUBMITTED
        }

        // 4. Persist the new state (optimistic version bump — see below).
        if err := q.UpdateTaskState(ctx, task.StateParams()); err != nil {
            return err
        }

        // 5. Append the ledger credit in the SAME transaction.
        //    (In-process; this is a direct store call, not a cross-module DB write —
        //     see note on module boundaries vs. transactions below.)
        if err := q.InsertLedgerEntry(ctx, task.RewardEntry()); err != nil {
            return err
        }

        // 6. Write the outbox event in the SAME transaction.
        return q.InsertOutboxEvent(ctx, outbox.From("TaskApprovedEvent", task.ApprovedPayload()))
    })
}
```

All six steps commit together or not at all. If the ledger insert fails, the task stays `SUBMITTED` and no event is emitted. If the process crashes after commit, the outbox row survives and the relay will deliver the event (see `docs/events-outbox.md`).

> **Boundary vs. transaction tension.** Step 5 writes a ledger row inside the task service's transaction. Doesn't that violate "no module writes another's tables"? For MVP we resolve this by making `TaskApprovedEvent` the credit trigger: the **ledger module** subscribes to the event and does the credit in *its own* transaction (step 5 above is illustrative of the atomic-effect shape, not the chosen cross-module path). The task transaction writes only task tables + the outbox row. The ledger credit happens in the ledger module's consumer, idempotent on the event key. **Follow the event path, not a cross-module write.** Keep step 5 for effects that live in the *same* module as the command.

## Locking strategy — chosen per aggregate

`sqlc` lets us write the exact SQL, so locking is a deliberate choice, visible in the query.

### Wallet → pessimistic (`FOR UPDATE`)

Concurrent cash-outs must not both pass the balance check. Lock the wallet row for the duration of the balance mutation:

```sql
-- name: GetWalletForUpdate :one
SELECT * FROM wallets WHERE teen_id = $1 AND currency = $2 FOR UPDATE;
```
The service compares the amount with *available* funds (balance minus locked minus open payout request) under that lock and returns a domain error if it does not fit. The `CHECK (balance_amount >= 0)` and `CHECK (locked_amount <= balance_amount)` constraints are the immutable backstop, mapped to the same domain error; the row lock is what serializes the read-modify-write. This is the PRD's chosen mechanism (architecture doc §3). It is correct at the app's scale; the footnote's high-scale concern is real but distant.

### Task state → optimistic (version column)

Two parents (or a double-tap) approving the same task concurrently should not both succeed. Rather than hold a row lock, detect the conflict:

```sql
-- name: UpdateTaskState :execrows
UPDATE tasks
SET state = $2, version = version + 1, decided_by = $3, updated_at = now()
WHERE id = $1 AND version = $4;
```
Zero rows affected → someone transitioned it first → return `ErrConcurrentModification`, let the caller retry or report a conflict. Optimistic locking fits state transitions (short, rarely contended) better than holding a lock, and it surfaces the race explicitly.

**Rule of thumb:** money mutations → pessimistic; state-machine transitions → optimistic. Document the choice in the query's comment.

## Idempotency — a cross-cutting requirement (PRD footnote [d])

Every reward, payout, penalty, and file upload is idempotent. Three layers:

1. **Client-provided key.** The client sends an idempotency key (UUID) or we derive a deterministic one (`REWARD-TASK-{taskID}`, `PAYOUT-{payoutID}`). Same logical operation → same key. Recurring tasks are one row per occurrence, so `taskID` is already unique per period; never derive a key from a timestamp, since a retry would mint a different key and defeat the guard.
2. **`UNIQUE` constraint.** An `idempotency_keys` table (or a `UNIQUE` column on the effect table) makes a replay a constraint violation, caught and treated as success. The guard runs *inside* the transaction so the key and the effect commit together — you can never record the key without the effect or vice versa.
3. **Idempotent event consumers.** Because the outbox is at-least-once, consumers must dedupe on the event's key too. The ledger's `TaskApprovedEvent` consumer uses the event key as the ledger entry's idempotency key — a redelivered event inserts nothing the second time.

### File-upload idempotency

Per the PRD "Important" note, a retried upload must not create a duplicate object. The client sends an `Idempotency-Key` header with the submit request; the media module derives the S3 object key deterministically from it, so a retry overwrites the same key rather than creating a new object. No DB uniqueness needed — the object store's key space enforces it.

Ordering for proof submission: **upload first, then transaction.** `media.Storage.Put` runs outside the DB transaction (it is idempotent and safe to repeat); then one `execTx` applies the FSM transition, stores the proof reference, and writes the outbox event. A failure after the upload leaves at most an orphaned object that the retry overwrites. Never hold a DB transaction open across the S3 call.

## Isolation level

Default (`READ COMMITTED`) + explicit row locks is correct for every flow here; we rely on `FOR UPDATE` and optimistic version checks, not on `SERIALIZABLE`. Do not raise the isolation level globally — it trades throughput for a guarantee we already get locally. If a specific flow ever needs `REPEATABLE READ`/`SERIALIZABLE`, set it on that transaction only (`BeginTx` options) and document why.

## Checklist for any new state-changing command

- [ ] One `execTx`; the boundary is the service method.
- [ ] Idempotency key inserted inside the transaction, duplicate = no-op success.
- [ ] Aggregate loaded with the correct lock (`FOR UPDATE` for money, version check for state).
- [ ] FSM transition is a pure domain method returning an error on an illegal move.
- [ ] Any cross-module effect is an outbox event written in the same transaction — never a write to another module's table.
- [ ] The event's `aggregate_id` is the id of a row this transaction updates or locks, so events of one aggregate are ordered (`docs/events-outbox.md`).
- [ ] No `execTx` nested inside another.
