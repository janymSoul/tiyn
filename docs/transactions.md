# Transactions, Locking & Idempotency

This is the most important document for correctness. The ledger is money (virtual, but disputes between parent and teen are real). The rules here are not negotiable without an architecture discussion.

## The one rule

**The transaction boundary lives in the feature's service, and nowhere else.** A service method opens exactly one transaction, does all its work inside it, and commits or rolls back. Domain code is pure and opens nothing. Stores run inside whatever transaction the service opened and never start one. Controllers open nothing.

Spring's default propagation (`REQUIRED`) would silently join a nested call to the outer transaction, which hides where a transaction begins. Keep it simple: only service methods are `@Transactional` (or use a `TransactionTemplate`), a service never calls another service's transactional method, and a store method is never annotated.

## Two ways to open the transaction

**`@Transactional` on the service method** is the default for a command that does not retry:

```java
@Transactional
public void approveTask(ApproveTask cmd) { ... }
```

**`TransactionTemplate`** when the command retries on a conflict. A unique violation aborts the Postgres transaction, so a retry must start a fresh one; an annotation on the method cannot do that. `AccountService` and `FamilyService` are the references, sharing `platform.tx.ConflictRetry`:

```java
User user = ConflictRetry.run(
        () -> signInOnce(identity, role, displayName),
        () -> new IdentityException(IdentityError.CONFLICT));

private User signInOnce(...) {
    return tx.execute(status -> { ... });
}
```
`ConflictRetry` retries up to five times when a store throws `ConflictException` (a unique violation), and gives up with the error you supply.

jOOQ's `DSLContext` is bound to the Spring-managed datasource, so every statement inside the template or annotated method joins the same transaction automatically. Never create a second `DSLContext` or open a connection by hand inside a transaction.

### Never nest transactions

A second transaction grabs a second pooled connection and can self-deadlock (the outer holds a lock the inner waits on). Rule: **services open transactions; domain and store never do.** If one service must compose another's work in the same transaction, it calls a plain method on the shared store, not the other service. In practice, cross-service composition should be async (events), so this rarely arises.

## The canonical flow: approve a task

This is the shape every state-changing command follows — guard the FSM, persist the new state, and emit the event, all atomically.

```java
@Transactional
public void approveTask(ApproveTask cmd) {
    if (!idempotencyKeys.claim(cmd.idempotencyKey())) {
        return;                                              // 1. duplicate command: no-op success
    }
    Task task = tasks.findForUpdate(cmd.taskId());           // 2. load the aggregate
    authorize(cmd.parentId(), task);                         //    FamilyDirectory.areLinked
    task.approve(cmd.parentId(), clock.instant());           // 3. pure FSM transition, records TaskApproved
    tasks.save(task);                                        // 4. persist with the version check, then publish
}
```

All of it commits together or not at all (the store publishes inside the transaction, after the write). If the save fails, the task stays `SUBMITTED` and no event is published. If the process crashes after commit, the publication row survives and Modulith redelivers it on restart (`docs/events-outbox.md`).

`idempotencyKeys.claim` is `INSERT ... ON CONFLICT DO NOTHING` and returns whether a row was inserted. **Do not catch a unique violation inside a transaction to detect a duplicate:** Postgres marks the transaction aborted and every later statement fails. Use `ON CONFLICT` for "already exists is fine", and let a violation propagate where it means a race (then retry the whole transaction as `AccountService` does).

> **Boundary vs. transaction tension.** The ledger credit is an effect in another module. The task transaction writes only task tables and the published event; the **ledger module** listens for `TaskApproved` and credits the wallet in *its own* transaction, idempotent on the reward key. **Follow the event path, not a cross-module write.** Keep an in-transaction effect for things that live in the *same* module as the command.

## Locking strategy — chosen per aggregate

jOOQ lets us write the exact SQL, so locking is a deliberate choice, visible in the query.

### Wallet → pessimistic (`FOR UPDATE`)

Concurrent cash-outs must not both pass the balance check. Lock the wallet row for the duration of the balance mutation:

```java
dsl.selectFrom(WALLETS)
        .where(WALLETS.TEEN_ID.eq(teenId).and(WALLETS.CURRENCY.eq(currency)))
        .forUpdate()
        .fetchSingle();
```
The service compares the amount with *available* funds (balance minus locked minus open payout request) under that lock and returns a domain error if it does not fit. The `CHECK (balance_amount >= 0)` and `CHECK (locked_amount <= balance_amount)` constraints are the immutable backstop, mapped to the same domain error; the row lock is what serializes the read-modify-write. This is the PRD's chosen mechanism (architecture doc §3). It is correct at the app's scale; the footnote's high-scale concern is real but distant.

**Lock order for payouts: the wallet row first, then the payout row**, in every command (request, fulfil, cancel, decline, expiry, unlink), so two commands can never wait on each other.

The same pattern is used for the identity `family_links` row, so concurrent join/unlink of one pair are serialised.

### Task state → optimistic (version column)

Two parents (or a double-tap) approving the same task concurrently should not both succeed. Rather than hold a row lock, detect the conflict:

```java
int updated = dsl.update(TASKS)
        .set(TASKS.STATE, task.state().name())
        .set(TASKS.VERSION, TASKS.VERSION.plus(1))
        .where(TASKS.ID.eq(task.id()).and(TASKS.VERSION.eq(task.version())))
        .execute();
if (updated == 0) {
    throw new ConcurrentModificationException(task.id());
}
```
Zero rows affected → someone transitioned it first → return a conflict, let the caller retry or report it. Optimistic locking fits state transitions (short, rarely contended) better than holding a lock, and it surfaces the race explicitly.

**Rule of thumb:** money mutations → pessimistic; state-machine transitions → optimistic. Name the store method after the choice (`findForUpdate`, `saveVersioned`) rather than commenting it.

## Idempotency — a cross-cutting requirement (PRD footnote [d])

Every reward, payout, penalty, and file upload is idempotent. Three layers:

1. **Client-provided key.** The client sends an idempotency key (UUID) or we derive a deterministic one (`REWARD-TASK-{taskId}`, `PAYOUT-{payoutId}`). Same logical operation → same key. Recurring tasks are one row per occurrence, so `taskId` is already unique per period; never derive a key from a timestamp, since a retry would mint a different key and defeat the guard.
2. **`UNIQUE` constraint.** An `idempotency_keys` table (or a `UNIQUE` column on the effect table) makes a replay a no-op or a conflict, handled as success. The guard runs *inside* the transaction so the key and the effect commit together — you can never record the key without the effect or vice versa.
3. **Idempotent event consumers.** Delivery is at-least-once, so consumers must dedupe too. The ledger's `TaskApproved` listener uses `REWARD-TASK-{taskId}` as the ledger entry's idempotency key — a redelivered event inserts nothing the second time.

### File-upload idempotency

Per the PRD "Important" note, a retried upload must not create a duplicate object. The client sends an `Idempotency-Key` header with the submit request; the media module derives the S3 object key deterministically from it, so a retry overwrites the same key rather than creating a new object. No DB uniqueness needed — the object store's key space enforces it.

Ordering for proof submission: **upload first, then transaction.** `Storage.put` runs outside the DB transaction (it is idempotent and safe to repeat); then one transactional service call applies the FSM transition, stores the proof reference, and publishes the event. A failure after the upload leaves at most an orphaned object that the retry overwrites. Never hold a DB transaction open across the S3 call, which also means the S3 call must not sit inside a `@Transactional` method.

## Isolation level

Default (`READ COMMITTED`) + explicit row locks is correct for every flow here; we rely on `FOR UPDATE` and optimistic version checks, not on `SERIALIZABLE`. Do not raise the isolation level globally — it trades throughput for a guarantee we already get locally. If a specific flow ever needs `REPEATABLE READ`/`SERIALIZABLE`, set it on that transaction only and document why.

## Checklist for any new state-changing command

- [ ] One transaction; the boundary is the service method.
- [ ] Idempotency key claimed inside the transaction with `ON CONFLICT DO NOTHING`; duplicate = no-op success.
- [ ] Aggregate loaded with the correct lock (`FOR UPDATE` for money, version check for state).
- [ ] FSM transition is a pure domain method returning events or throwing a domain error on an illegal move.
- [ ] Any cross-module effect is a published event in the same transaction — never a write to another module's table.
- [ ] Reads that authorise the command (roles, links) happen inside the same transaction as the write, or the stale-read risk is accepted and written down.
- [ ] No transaction nested inside another, and no network call inside one.
