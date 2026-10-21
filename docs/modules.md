# Modules & Boundaries

The system is five bounded contexts. Each is a deep module: a small interface over substantial, hidden implementation. This document defines what each module *is*, what it *offers* (its interface contract), what it *publishes* (its event contract), and the rules that keep the boundaries clean so a module can later be lifted into its own service.

## The two — and only two — ways modules interact

1. **Synchronous:** Module A depends on an **interface** that Module B exports (e.g. `media.PresignService`). A receives the concrete implementation by constructor injection, wired in `cmd/api/main.go`. A imports *only* B's exported interface and event types — never B's `domain/`, `store/`, or `http/`.
2. **Asynchronous:** Module A publishes a domain event; Module B subscribes. A and B do not know about each other at all. This is the default for anything that is a *reaction* ("when a task is approved, credit the ledger and notify").

Prefer async for reactions and side effects; use sync only when the caller genuinely needs a result back *now* (e.g. "give me a pre-signed upload URL").

## Hard rules (these are what make later extraction mechanical)

- **No shared tables.** Each table is owned by exactly one module. No other module reads or writes it.
- **No cross-context foreign keys.** A `ledger_entries` row references a task by `task_id` as a plain UUID column, not a FK to the task module's table. Cross-context references are by ID only. (Within a single module, FKs are encouraged.)
- **No distributed transaction across modules.** A single DB transaction belongs to one module's service call. Cross-module consistency is achieved with events + idempotency (eventual consistency), never a transaction spanning two modules' tables.
- **IDs are the integration currency.** Modules exchange UUIDs and small value objects in event payloads, not entities.

If you find yourself wanting to JOIN across two modules' tables, that is the signal that either the boundary is wrong or the data should travel in an event. Ask before reaching across.

---

## 1. Identity & Family

**Responsibility.** User accounts (parent, teen), authentication (JWT issue + verify), and family-group linking via a 6-digit alphanumeric code (PAR-01). Owns the security context that every other request is authorized against.

**Offers (sync interface):**
```go
type Authenticator interface {
    // Verify parses and validates a bearer token, returning the caller's identity.
    Verify(ctx context.Context, bearer string) (Principal, error)
}
type FamilyDirectory interface {
    // AreLinked reports whether a parent may act on a given teen's behalf.
    AreLinked(ctx context.Context, parentID, teenID uuid.UUID) (bool, error)
}
```
Other modules depend on `FamilyDirectory` to authorize parent→teen actions (e.g. the task module checks a parent may approve a given teen's task). `Authenticator` is used by the HTTP auth middleware.

**Publishes:** `FamilyLinkedEvent{ParentID, TeenID}` (lets other modules react to a new link, e.g. seed a wallet).

**Owns:** `users`, `families`, `family_links`, `link_codes`.

---

## 2. Task & Milestone

**Responsibility.** The lifecycle of chores and academic milestones (PAR-02, PAR-03, TEN-02): creation, submission with proof, parental review. This is the primary **state machine** in the system.

**Task FSM:**
```
DRAFT ──create──▶ ASSIGNED ──submit(proofURL)──▶ SUBMITTED
                                                     │
                      ┌──────────────────────────────┼─────────────────────┐
                      ▼ approve                       ▼ reject               ▼ request_redo
                  APPROVED                         REJECTED              ASSIGNED
                  (terminal for                    (terminal)           (back to work)
                   one-time tasks;
                   recurring tasks
                   re-arm to ASSIGNED)
```
Guards live in the domain: you can only `submit` from `ASSIGNED`, only `approve`/`reject`/`request_redo` from `SUBMITTED`. Invalid transitions return `ErrInvalidTransition`.

**Offers (sync interface):** none required by other modules today. Keep it that way unless a real need appears — a module that offers nothing synchronous is maximally decoupled.

**Publishes:**
- `TaskSubmittedEvent{TaskID, TeenID, ProofURL}` → notification (tell the parent), 
- `TaskApprovedEvent{TaskID, TeenID, RewardKZT, IdempotencyKey}` → ledger (credit), notification (tell the teen). *This is the central event of the system* (architecture doc calls it `TaskCompletedEvent`; we name it `TaskApprovedEvent` for precision).
- `TaskRejectedEvent`, `TaskRedoRequestedEvent` → notification.

**Depends on:** `identity.FamilyDirectory` (authorize that the approver parents this teen), `media.PresignService` (issue the proof-upload URL at submission start).

**Owns:** `tasks`, `milestones`, `task_proofs`. Uses an **optimistic version column** on `tasks` to detect concurrent transitions (see `docs/transactions.md`).

---

## 3. Ledger & Wallet

**Responsibility.** The append-only transaction journal and the derived wallet balance (TEN-01, SYS-01). Credits from approved tasks, debits from payouts and penalties (PAR-04), payout holds, and savings-goal locks (TEN-04). This module is the financial source of truth.

**Design:** double-entry-style append-only `ledger_entries` (never updated or deleted — corrections are compensating entries). A `wallets` row caches the balance with `CHECK (cached_balance_kzt >= 0)` and is mutated only under `SELECT … FOR UPDATE` (pessimistic lock). Every credit/debit carries an idempotency key with a `UNIQUE` constraint.

**Payout FSM (held funds):**
```
REQUESTED ──parent fulfils──▶ FULFILLED        (funds debited on fulfilment confirmation)
     │
     └──cancel/expire──▶ CANCELLED             (hold released)
```

**Offers (sync interface):**
```go
type BalanceReader interface {
    Balance(ctx context.Context, teenID uuid.UUID) (Balance, error) // approved + pending
}
```
(The teen dashboard reads balance; expose read-only. All *writes* arrive via events or payout commands, never a generic "setBalance".)

**Publishes:** `PayoutRequestedEvent{PayoutID, TeenID, ParentID, AmountKZT}` → notification. `PayoutFulfilledEvent`, `PenaltyAppliedEvent`.

**Subscribes:** `TaskApprovedEvent` → credit the teen's wallet (idempotent on the event's key). `FamilyLinkedEvent` → create the teen's wallet.

**Owns:** `ledger_entries`, `wallets`, `payouts`, `savings_goals`, `idempotency_keys`.

---

## 4. Media Storage

**Responsibility.** S3 client wrapper (SYS-02). Issues 15-minute pre-signed PUT URLs for proof uploads and GET URLs for review. The backend never handles image bytes.

**Offers (sync interface):**
```go
type PresignService interface {
    // PresignUpload returns a PUT URL for a new proof object. The uploadKey is
    // derived deterministically from an idempotency key so a retried request
    // cannot create a duplicate object (PRD "Important" note).
    PresignUpload(ctx context.Context, idemKey string, contentType string) (Upload, error)
    PresignView(ctx context.Context, objectKey string) (url string, err error)
}
```
Called by the task module when a teen begins a submission.

**Idempotent upload:** the object key is a deterministic function of the idempotency key (e.g. `proofs/{familyID}/{sha256(idemKey)}`). A retried presign for the same key returns a URL to the same object — a failed upload that retries never produces two objects.

**Owns:** no DB tables of its own in MVP (object keys are referenced by the task module as plain strings). If upload bookkeeping is later needed, it owns its own `media_objects` table.

**Publishes:** nothing in MVP.

---

## 5. Notification Dispatcher

**Responsibility.** Asynchronous push delivery to FCM/APNs (SYS-03). Purely reactive: it subscribes to domain events and turns them into localized push messages. The *only* module that knows about FCM.

**Offers (sync interface):** none. It is a pure consumer.

**Subscribes:** `TaskSubmittedEvent` (→ parent: "Proof pending"), `TaskApprovedEvent` (→ teen: "Reward credited"), `TaskRejectedEvent`/`TaskRedoRequestedEvent` (→ teen), `PayoutRequestedEvent` (→ parent: high-priority "Payout request"), `PayoutFulfilledEvent` (→ teen). `FamilyLinkedEvent` (→ welcome).

**Localization:** picks RU/KK copy per the recipient's locale (read from a projection it maintains from `FamilyLinkedEvent`/identity, or carried on the event — decide when implementing; prefer carrying `locale` on the event to avoid a sync call back to identity).

**Owns:** `device_tokens`, and any notification outbox/dedup table it needs to be idempotent (a device must not get the same push twice from a redelivered event).

---

## Module dependency graph (sync edges only)

```
http middleware ──▶ identity.Authenticator
task.service ──▶ identity.FamilyDirectory
task.service ──▶ media.PresignService
http (dashboard) ──▶ ledger.BalanceReader
```
No cycles. All other relationships are event-driven (dashed, via the bus): task→ledger, task→notification, ledger→notification, identity→ledger, identity→notification.

Keeping the sync graph this thin is deliberate: it is the set of edges you must sever to extract a module. The fewer, the cheaper the extraction.

## Extraction checklist (future — not MVP work)

When a module must become its own service:
1. Its sync interface becomes a network API (gRPC/REST); callers swap the in-process impl for a client.
2. Its event subscriptions/publications swap the Watermill GoChannel backend for Google Pub/Sub — handler code is unchanged.
3. Its tables move with it; because there were no cross-context FKs, nothing in other schemas breaks.
4. Its idempotency guarantees already assume at-least-once, so network redelivery changes nothing.

If every rule in this doc was followed, extraction touches wiring and transport — not domain logic.
