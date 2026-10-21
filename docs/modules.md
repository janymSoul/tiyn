# Modules & Boundaries

The system is five bounded contexts. Each is a deep module: a small interface over substantial, hidden implementation. This document defines what each module *is*, what it *offers* (its interface contract), what it *publishes* (its event contract), and the rules that keep the boundaries clean so a module can later be lifted into its own service.

## The two — and only two — ways modules interact

1. **Synchronous:** Module A depends on an **interface** that Module B exports (e.g. `media.Storage`). A receives the concrete implementation by constructor injection, wired in `cmd/api/main.go`. A imports *only* B's exported interface and event types — never B's `domain/`, `store/`, or `http/`.
2. **Asynchronous:** Module A publishes a domain event; Module B subscribes. A and B do not know about each other at all. This is the default for anything that is a *reaction* ("when a task is approved, credit the ledger and notify").

Prefer async for reactions and side effects; use sync only when the caller genuinely needs a result back *now* (e.g. "store this proof image and give me its object key").

## Hard rules (these are what make later extraction mechanical)

- **No shared tables.** Each table is owned by exactly one module. No other module reads or writes it.
- **No cross-context foreign keys.** A `ledger_entries` row references a task by `task_id` as a plain UUID column, not a FK to the task module's table. Cross-context references are by ID only. (Within a single module, FKs are encouraged.)
- **No distributed transaction across modules.** A single DB transaction belongs to one module's service call. Cross-module consistency is achieved with events + idempotency (eventual consistency), never a transaction spanning two modules' tables.
- **IDs are the integration currency.** Modules exchange UUIDs and small value objects in event payloads, not entities.

If you find yourself wanting to JOIN across two modules' tables, that is the signal that either the boundary is wrong or the data should travel in an event. Ask before reaching across.

## Domain model (DDD)

We apply Domain-Driven Design pragmatically: strategic DDD everywhere (bounded contexts, a context map, one language), tactical DDD (aggregates, value objects, domain events) where there are real rules to protect. The rules for writing it are in `docs/conventions.md`.

**Bounded contexts and the context map.** Each module below is a bounded context with its own model; the same word can mean different things in different contexts (a "user" in identity is an account, in notification it is just a recipient with device tokens). Contexts relate in two ways:
- **Published language:** the events in `docs/events-outbox.md` are the shared language between contexts. Task is upstream of ledger and notification; ledger is upstream of notification; identity is upstream of ledger and notification. Downstream contexts translate events into their own model and never depend on the upstream's internals.
- **Customer/supplier:** the few synchronous interfaces (`FamilyDirectory`, `BalanceReader`, `PendingReader`, `media.Storage`) are narrow contracts owned by the supplier.
- **Anti-corruption layer:** external systems (Google ID tokens, FCM, S3, bank apps) sit behind an interface in the module that uses them. Their types and vocabulary never enter `domain/`.

**Aggregates.** An aggregate is a cluster of objects that must be consistent together. Only its root is loaded and saved; other aggregates are referenced by ID.

| Context | Aggregate (root) | Contains | Invariants it protects | Concurrency |
|---|---|---|---|---|
| identity | `User` | join code | one Google subject, one user; a parent has one join code | unique constraints |
| identity | `FamilyLink` | parent id, teen id, removed flag | a parent and a teen are linked at most once; a link is not to oneself | primary key |
| task | `Task` | proofs | legal state transitions; tasks in a milestone are one-time and for the same teen | version column |
| task | `TaskSeries` | recurrence rule | one occurrence per period | unique `(series, date)` |
| ledger | `Wallet` | ledger entries (append-only), payouts, savings goals | balance never negative; locked never above balance; at most one open payout; one reward per task; one debit per payout | wallet row `FOR UPDATE` |
| notification | `DeviceToken` | token, platform, locale | a token belongs to one user | unique token |

The wallet aggregate is deliberately large: "available funds = balance − locked − open payout request" spans payouts, goals and entries, so they share one consistency boundary and one lock.

**Rule of thumb:** one transaction changes one aggregate. Documented exceptions inside a single context: cancelling a milestone also cancels its open tasks, and a payout request touches the wallet and its payout together (same aggregate). Across contexts there is never one transaction; use an event.

**Ubiquitous language.** Use these words in code, SQL, API names, events and docs, and do not invent synonyms.

| Term | Meaning |
|---|---|
| Parent / Teen | The two roles. A parent funds and approves; a teen earns. |
| Join code | A parent's permanent code a teen enters to link to that parent. |
| Link | The relation between one parent and one teen. |
| Task | A piece of work with a reward and a deadline. |
| Milestone | A task of kind milestone; larger goal with its own proof and reward. A task may belong to one. |
| Series / Occurrence | A recurring rule and one concrete task generated from it for a period. |
| Proof | What the teen submits to show a task is done (image or text). |
| Reward | The amount the teen earns when a task is approved. |
| Approve / Reject / Request redo / Cancel / Expire | The parent's review decisions, and the system's deadline outcome. |
| Wallet | A teen's account in one currency. |
| Balance | Money the teen has earned and not yet paid out. |
| Locked | Part of the balance set aside in savings goals. |
| Available | Balance minus locked minus the open payout request; what can be requested or locked. |
| Pending | Rewards of tasks submitted and not yet decided. Owned by task, not ledger. |
| Ledger entry | One immutable line in the wallet's history. |
| Payout | A teen's request to cash out; the parent fulfils it in their bank app and confirms. |
| Penalty | A parent's manual deduction. |
| Savings goal | A target the teen sets aside part of the balance for. |
| Domain event | A past-tense fact one context publishes, e.g. `TaskApprovedEvent`. |

---

## 1. Identity & Family

**Responsibility.** User accounts (parent, teen), authentication, and family-group linking (PAR-01). Owns the security context that every other request is authorized against.

**Authentication.** Sign-in with Google. The client obtains a Google ID token and sends it to the backend; identity verifies it and issues the app's own JWT. Google is only the proof of identity, so the provider can be swapped or extended (e.g. Apple) without touching other modules. Parents and teens both register by signing in; the role (`PARENT` or `TEEN`) is chosen at first sign-in and an account has exactly one role for life. Signing in again ignores any role in the request and returns the existing account. `GoogleVerifier` is the anti-corruption layer: the real implementation validates the ID token against Google's keys for the configured client ids (`GOOGLE_CLIENT_IDS`, one per platform), and tests use a fake. No email is stored; the display name comes from the request or the Google profile.

**Session token.** After a successful sign-in the backend issues its own JWT: HS256, signed with `JWT_SECRET` (at least 32 bytes), issuer `tiyn`, subject = user id, a `role` claim, valid for `JWT_TTL` (default 7 days). Verification pins the algorithm, requires an expiry, the issuer and a known role, so `alg: none` and other-algorithm tokens are rejected. There is no refresh token: when it expires the client signs in with Google again. The token only proves identity; every parent-to-teen action still checks `AreLinked` in the database. Moving to an identity provider (e.g. Keycloak) later changes only `GoogleVerifier`, `TokenIssuer` and the sign-in route.

**Linking.** Every parent has a permanent **join code** (6 characters, PRD PAR-01) stored on their user row. It uses an alphabet without look-alike characters (no `0/O`, `1/I`) and is generated with a retry on collision. A teen who has signed in enters the parent's code and identity creates the `family_links` row immediately and publishes `FamilyLinkedEvent`; entering a code a second time is a no-op. Codes are matched case-insensitively and ignoring surrounding spaces. One code can link any number of children, and a teen can enter a second parent's code. The parent can remove a link later. Code attempts are rate-limited at the ingress and failures never reveal whether a code exists. Accepted risk: the code is permanent and reusable, so anyone who learns it can link to that parent. Parent confirmation of each join (a pending-request step) is deliberately deferred; it would add an identity-owned `join_requests` table without changing other modules.

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
Links are soft-deleted (`removed_at`). `AreLinked` and every other lookup must ignore removed links; re-linking a removed pair clears `removed_at` on the existing row and increments its `generation`, which is part of the event key so that a relink produces a new event.
Other modules depend on `FamilyDirectory` to authorize parent→teen actions (e.g. the task module checks a parent may approve a given teen's task). `Authenticator` is used by the HTTP auth middleware.

**Publishes:**
- `FamilyLinkedEvent{ParentID, TeenID}` (lets other modules react to a new link, e.g. seed a wallet). Key `FAMILY-LINKED-{parent}-{teen}-{generation}`.
- `FamilyUnlinkedEvent{ParentID, TeenID}` (the task module cancels the teen's open tasks for that parent). Key `FAMILY-UNLINKED-{parent}-{teen}-{generation}`.

Both use an aggregate id derived from the (parent, teen) pair, and are written in the transaction that locks the link row, so the events of one pair stay ordered.

**HTTP API.** Errors are `{"error": "<code>"}`; bodies are size-limited and reject unknown fields.

| Route | Auth | Purpose | Errors |
|---|---|---|---|
| `POST /auth/google` `{idToken, role?, displayName?}` | none | Sign in or register; returns `{token, user}` | 401 `invalid_identity`, 422 `invalid_role` / `invalid_display_name` |
| `GET /me` | bearer | The caller's account | 401 `unauthenticated` |
| `POST /links/join` `{code}` | teen | Link to the parent who owns the code; returns `{parent: {displayName}}` | 403 `forbidden`, 422 `invalid_join_code` (unknown and malformed codes look the same) |
| `DELETE /links/{teenId}` | parent | Remove the link to a teen; repeating it is harmless (204) | 403 `forbidden`, 404 `not_found`, 400 `invalid_teen_id` |

**Configuration:** `JWT_SECRET`, `JWT_TTL`, `GOOGLE_CLIENT_IDS` (see `.env.example`).

**Owns:** `users` (including the parents' `join_code`), `family_links`. There is no `families` table: a family is simply the set of links between parents and teens, so a teen can have two parents without a new concept.

---

## 2. Task & Milestone

**Responsibility.** The lifecycle of chores and academic milestones (PAR-02, PAR-03, TEN-02): creation, submission with proof, parental review, cancellation and expiry. This is the primary **state machine** in the system.

A milestone is a task with `kind = MILESTONE`: same FSM, same review flow, different grouping in the teen UI. One table, one FSM.

**Tasks inside a milestone.** A task may belong to a milestone through a nullable `milestone_id` (a self-reference within the task module). Each such task has its own reward and goes through its own review; the milestone is approved separately, with its own proof and reward, and does not auto-complete when its tasks are done. Rules enforced in the service: one level only (a milestone's tasks cannot themselves be milestones or hold tasks), a task's deadline is not later than its milestone's, the milestone must be `ASSIGNED` when a task is added, and tasks inside a milestone are one-time, never recurring (also enforced by a CHECK). Cancelling a milestone cancels its open tasks in the same transaction, each emitting `TaskCancelledEvent`; cancelling a task does not affect the milestone. Expiry does not cascade, since a task's deadline never exceeds its milestone's. Progress ("3 of 5 approved") is computed from the tasks.

**Task FSM:**
```
 create ──▶ ASSIGNED ──submit(proof)──▶ SUBMITTED ──approve──▶ APPROVED   (terminal)
               ▲  │                        │  │
               │  │                        │  └────reject───▶ REJECTED   (terminal)
               │  │                        │
               │  │                        └─request_redo─┐
               │  └────────────────────────────────────────┘ (back to ASSIGNED)
               │
               ├── expire (system, deadline passed) ──▶ EXPIRED     (terminal)
               └── cancel (parent, from ASSIGNED or SUBMITTED) ──▶ CANCELLED (terminal)
```
There is no `DRAFT`: a task is created directly in `ASSIGNED`. Guards live in the domain: `submit` only from `ASSIGNED`; `approve`/`reject`/`request_redo` only from `SUBMITTED`; `expire` only from `ASSIGNED` (a task submitted before its deadline stays approvable however slow the review); `cancel` from `ASSIGNED` or `SUBMITTED`. Invalid transitions return `ErrInvalidTransition`.

**Recurrence: one row per occurrence.** A recurring task (daily/weekly) is a *series*; each period is its own `tasks` row with `series_id` and `occurrence_date` and a `UNIQUE (series_id, occurrence_date)` constraint. Terminal states never "re-arm" — the next period is a new row. This keeps history, makes the reward key (`REWARD-TASK-{taskID}`) naturally unique per occurrence, and makes materialising the next occurrence idempotent. Period boundaries are computed in the series' own timezone (`task_series.timezone`) and stored in UTC. A missed occurrence expires; it carries no automatic penalty (penalties are manual, PAR-04).

**Proof submission.** The client sends the image (or text) in the submit request with an `Idempotency-Key`. The task service passes the bytes to `media.Storage.Put` first (idempotent by key), then runs one transaction that applies the FSM transition, stores `task_proofs`, and writes the outbox event. If the transaction fails, a retry re-puts to the same object key (overwrite, no duplicate) and tries the transition again.

**Offers (sync interface):**
```go
type PendingReader interface {
    // PendingReward is the sum of rewards of the teen's SUBMITTED tasks.
    PendingReward(ctx context.Context, teenID uuid.UUID) (kzt int64, err error)
}
```
Used by the dashboard handler to show "Pending" funds (TEN-01). Pending is task-owned data, so the task module answers it; the ledger never reads task tables.

**Publishes:**
- `TaskAssignedEvent{TaskID, TeenID, Kind, RewardAmount, Currency}` → notification (tell the teen: "new task / milestone").
- `TaskSubmittedEvent{TaskID, TeenID, ProofRef}` → notification (tell the parent).
- `TaskApprovedEvent{TaskID, TeenID, RewardAmount, Currency, IdempotencyKey}` → ledger (credit), notification (tell the teen). *This is the central event of the system* (architecture doc calls it `TaskCompletedEvent`; we name it `TaskApprovedEvent` for precision).
- `TaskRejectedEvent`, `TaskRedoRequestedEvent`, `TaskCancelledEvent`, `TaskExpiredEvent` → notification.

**Time-driven work** (`Ticker`, see below): expire `ASSIGNED` tasks past their deadline; materialise the next occurrence of each recurring series.

**Depends on:** `identity.FamilyDirectory` (authorize that the caller parents this teen), `media.Storage` (store the proof image).

**Owns:** `tasks`, `task_series`, `task_proofs`, `idempotency_keys`. Uses an **optimistic version column** on `tasks` to detect concurrent transitions (see `docs/transactions.md`).

---

## 3. Ledger & Wallet

**Responsibility.** The append-only transaction journal and the derived wallet balance (TEN-01, SYS-01). Credits from approved tasks, debits from payouts and penalties (PAR-04), payout requests, and savings-goal locks (TEN-04). This module is the financial source of truth.

**Money representation.** Amounts are `BIGINT` in minor units (tiyn for KZT) and always travel with a `currency` (a Postgres domain `platform.currency`, currently restricted to `KZT`). Allowing another currency is a one-line change to that domain plus the product work around it (wallet per currency, no cross-currency sums, payouts only in the currency the parent can transfer). Payouts and wallets are KZT today because payouts are Kaspi transfers in tenge. A goal priced in another currency would be a display conversion at an exchange rate, not a second ledger currency, and is not built.

**Design:** double-entry-style append-only `ledger_entries` (never updated or deleted — corrections are compensating entries). A `wallets` row per teen and currency caches the balance with `CHECK (balance_amount >= 0)` and is mutated only under `SELECT … FOR UPDATE` (pessimistic lock). Every credit/debit carries an idempotency key with a `UNIQUE` constraint.

**Available balance.** What a teen may request or lock is
`available = approved balance − sum(open REQUESTED payouts) − funds locked in savings goals`.
Requesting a payout does not debit anything; it only reduces *available* (computed under the wallet lock). A teen may have **at most one** open request, enforced by a partial unique index on `payouts (teen_id) WHERE state = 'REQUESTED'`. The debit entry is written when the parent confirms.

**Debit checks.** Every debit (payout confirmation, penalty, goal lock) first compares the amount with available funds under the wallet lock and returns a domain error (HTTP 422) if it does not fit. The wallet `CHECK`s (`balance_amount >= 0`, `locked_amount <= balance_amount`) are a backstop, not the user-facing rule: a debit larger than available but smaller than the balance (funds locked in a goal) would only trip the second one. The store maps those constraint violations to the same domain error.

**Payout FSM:**
```
REQUESTED ──parent confirms──▶ FULFILLED   (debit entry written; parent's word is trusted)
     │
     └──teen cancels / parent declines / expires──▶ CANCELLED
```
A payout cancelled by the system (it expired) has no `resolved_by`; one cancelled by the teen or declined by the parent records who did it. Every cancellation records a `cancel_reason`. We never verify the bank transfer (no bank API, and we are not a bank), whichever bank the parent uses. Confirmation by the parent is the source of truth; the audit trail records who confirmed and when. A missing transfer is resolved between parent and teen.

**Offers (sync interface):**
```go
type BalanceReader interface {
    Balance(ctx context.Context, teenID uuid.UUID) (Balance, error) // Approved, Locked, Available
}
```
(Read-only. "Pending" is not here; it comes from `task.PendingReader`. All *writes* arrive via events or payout commands, never a generic "setBalance".)

**Publishes:** `PayoutRequestedEvent{PayoutID, TeenID, ParentID, Amount, Currency}` → notification. `PayoutFulfilledEvent`, `PayoutCancelledEvent`, `PenaltyAppliedEvent`.

**Subscribes:** `TaskApprovedEvent` → credit the teen's wallet (idempotent on the event's key). `FamilyLinkedEvent` → create the teen's wallet.

**Time-driven work** (`Ticker`): cancel `REQUESTED` payouts older than the expiry window.

**Owns:** `ledger_entries`, `wallets`, `payouts`, `savings_goals`. The ledger needs no separate idempotency table: `ledger_entries.idempotency_key` and `payouts.idempotency_key` are `UNIQUE` and are the guard.

**Savings goals.** A goal stores its target only. The amount saved is derived: the sum of `locked_delta` over the ledger entries whose `ref_id` is the goal, so goals and the wallet's locked amount cannot drift apart.

---

## 4. Media Storage

**Responsibility.** S3 client wrapper (SYS-02). Receives proof images from the task service, validates and sanitises them, stores them, and issues short-lived pre-signed GET URLs for review. Proof images pass through the backend (see "Why bytes pass through" below).

**Offers (sync interface):**
```go
type Storage interface {
    // Put validates (content-type allowlist, size cap), strips metadata such as
    // EXIF/GPS, and stores the image. The object key is a deterministic function
    // of scope + idemKey, so a retried Put overwrites the same object instead of
    // creating a duplicate (PRD "Important" note).
    Put(ctx context.Context, scope, idemKey, contentType string, body io.Reader) (objectKey string, err error)
    // PresignView returns a short-lived GET URL for parent review.
    PresignView(ctx context.Context, objectKey string) (url string, err error)
}
```
Called by the task module on submission and review.

**Idempotent upload:** object key = `proofs/{scope}/{sha256(idemKey)}`. A retry for the same key hits the same object. If the later DB transaction fails, the object is orphaned only until the retry overwrites it; a periodic sweep of unreferenced objects can be added if it ever matters.

**Why bytes pass through.** The teen client never sees S3, never holds an object key, and cannot submit a key for an object that was never uploaded. The server can enforce type and size and strip location metadata from photos of minors. Cost: image bytes use API bandwidth and memory, and the platform's request-size limit applies (set the ingress body limit accordingly). Clients compress before sending, so a few MB per request is expected. If scale demands it, switching to pre-signed PUT is a change inside this module plus the task submit path.

**Owns:** no DB tables of its own in MVP (object keys are referenced by the task module as plain strings). If upload bookkeeping is later needed, it owns its own `media_objects` table.

**Publishes:** nothing in MVP.

---

## 5. Notification Dispatcher

**Responsibility.** Asynchronous push delivery to FCM/APNs (SYS-03). Purely reactive: it subscribes to domain events and turns them into localized push messages. The *only* module that knows about FCM.

**Offers (sync interface):** none for domain callers. It exposes an endpoint for clients to register/refresh a device token and locale.

**Subscribes:** `TaskAssignedEvent` (→ teen), `TaskSubmittedEvent` (→ parent: "Proof pending"), `TaskApprovedEvent` (→ teen: "Reward credited"), `TaskRejectedEvent`/`TaskRedoRequestedEvent`/`TaskCancelledEvent`/`TaskExpiredEvent` (→ teen), `PayoutRequestedEvent` (→ parent: high-priority "Payout request"), `PayoutFulfilledEvent`/`PayoutCancelledEvent` (→ teen), `PenaltyAppliedEvent` (→ teen), `FamilyLinkedEvent` (→ welcome).

**Localization:** picks RU/KK copy per the recipient's locale, which the client sends with its device token. Events carry only IDs, so no call back into identity is needed.

**Owns:** `device_tokens`, and any notification outbox/dedup table it needs to be idempotent (a device must not get the same push twice from a redelivered event).

---

## Time-driven work

Some state changes happen because time passes, not because a request arrives: expiring overdue tasks, creating the next occurrence of a recurring task, cancelling stale payout requests, and publishing the outbox. No request wakes the process for these, so something must.

Each module with such work exposes a small interface and the composition root wires them together:
```go
type Ticker interface {
    // Tick performs all work due at `now`. It must be idempotent and safe to run
    // concurrently with itself (batches use FOR UPDATE SKIP LOCKED; transitions
    // are guarded by the FSM and version column).
    Tick(ctx context.Context, now time.Time) error
}
```
**Decision: a Kubernetes CronJob runs the same binary.** `tiyn tick` is a one-shot subcommand: it runs all domain tickers once and exits. It currently has none registered and only logs that; do not deploy the CronJob until the first ticker (task expiry) exists. A CronJob invokes it every minute with `concurrencyPolicy: Forbid` and an `activeDeadlineSeconds` shorter than the schedule interval. No HTTP endpoint is exposed and no extra service is needed.

**The outbox relay is the exception.** A CronJob's one-minute granularity would delay every event (reward credit, pushes) by up to a minute, which is too slow for the approve → credit → notify path. The relay therefore runs as an in-process loop (about 1 s) inside the API pods, which are always running on Kubernetes. The CronJob handles only work where minute-level precision is fine: task expiry, recurrence, payout expiry.

Because every `Tick` is idempotent and concurrency-safe, overlapping runs, a retried Job, or several API replicas all running the relay are harmless. The code stays platform-agnostic: the same `Ticker`s can be driven by an in-process loop (local dev) or any other scheduler if the platform changes.

---

## Module dependency graph (sync edges only)

```
http middleware ──▶ identity.Authenticator
task.service ──▶ identity.FamilyDirectory
task.service ──▶ media.Storage
http (dashboard) ──▶ ledger.BalanceReader
http (dashboard) ──▶ task.PendingReader
```
No cycles. All other relationships are event-driven (dashed, via the bus): task→ledger, task→notification, ledger→notification, identity→ledger, identity→notification.

Keeping the sync graph this thin is deliberate: it is the set of edges you must sever to extract a module. The fewer, the cheaper the extraction.

## Extraction checklist (future — not MVP work)

When a module must become its own service:
1. Its sync interface becomes a network API (gRPC/REST); callers swap the in-process impl for a client.
2. Its event subscriptions/publications swap the in-process dispatcher for a broker publisher and a Watermill router — handler code is unchanged.
3. Its tables move with it; because there were no cross-context FKs, nothing in other schemas breaks.
4. Its idempotency guarantees already assume at-least-once, so network redelivery changes nothing.

If every rule in this doc was followed, extraction touches wiring and transport — not domain logic.
