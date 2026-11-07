# ADR-0001: Make API requests safe to retry with a platform idempotency mechanism

- **Status:** accepted
- **Date:** 2026-11-07
- **Deciders:** self
- **Consulted:** none
- **Informed:** none

## Context and Problem Statement

The tiyn backend serves a mobile app on unreliable networks. A request can reach the server while its response is lost, so clients must be able to retry. Today that is only partly safe: `POST /tasks`, `/goals` and `/task-series` create a duplicate on retry, while submit, goal lock and unlock, and payouts already require an `Idempotency-Key`.

The existing mechanism is the task module's `task.idempotency_keys`. It only records that a key was seen, in one global key space with no owner, and it has no result to return. It works for submit because a repeat can read the task's current state. It cannot serve creation, where a replay must return the entity created the first time. Other modules (the wallet) handle idempotency separately.

The goal is a single rule for the whole API: any request is safe to retry. The backend's own rules constrain the answer: services own their transactions, events publish inside them, and a module never touches another module's tables. The data belongs to minors.

## Decision Drivers

- Any request should be safe to retry, and the mechanism must serve any entity, for both teen and parent callers.
- A retry after a lost response must not duplicate an effect.
- The key and the effect must commit or roll back together.
- Services own their transactions, and modules do not read each other's tables.
- Minimise stored personal data of minors.
- Keys only need to live for a day, so the table stays small and needs no partitioning.
- A new endpoint must not be able to skip the rule silently.

## Considered Options

- Option A: a unique key column on each entity table
- Option B: client-chosen entity ids
- Option C: content-based deduplication
- Option D: a universal filter that stores and replays whole responses
- Option E: one generic platform table plus a policy enforced by a test (chosen)
- Option F: client-side guard only

## Decision Outcome

Chosen option: **Option E**, because it makes every endpoint safe to retry with one shared mechanism, keeps the key and the effect in one transaction, and stores no response bodies.

The mechanism:

- A table owned by the `platform` module holds `user_id`, `key`, `operation`, `request_hash`, a nullable `resource_id` and `created_at`. The primary key is `(user_id, key)`, with an index on `created_at`.
- `operation` is an explicit name chosen by the owning service for each use case (for example `task.create`), not derived from the route, so renaming a route never breaks a retry. The fingerprint is a hash of the operation, the target resource id when the path has one, and the validated request record serialised in a stable form (not the raw JSON, whose field order, defaults and nulls vary). The same key with a different fingerprint is rejected as `invalid_idempotency_key`.
- The claim and the effect commit in the same transaction. If the effect fails, the claim rolls back and the client may retry. Concurrent requests with the same key wait on the unique index, so there is no "in progress" state.
- The table stores the id of the entity the request created or changed, not the response. A replay returns that entity's current state, reloaded by the module that owns it, because the platform module cannot read other modules' tables. A replay answers with the same status code as the original request (`201` for a create), so the client sees one outcome for one action.
- Keys expire after 24 hours (`IDEMPOTENCY_TTL`). A scheduled platform job deletes expired rows in small batches. The table is not partitioned.

The policy:

- Every `POST` that creates an entity or moves money requires an `Idempotency-Key`. An architecture test fails the build if a `POST` handler neither takes the idempotency context nor is explicitly marked naturally idempotent.
- Requests that are naturally idempotent take no key and are marked as such. Reject and cancel by the same parent, and unlinking an already unlinked teen, already return success when repeated (`Task.reject`, `Task.cancel`, `FamilyLink.unlink`), so they need no change. A repeated request-redo returns `409 invalid_transition`, because the task is already back in `ASSIGNED`; the client treats that as "the task changed" and reloads, which is accepted.

### Consequences

- Good, because one mechanism serves every module and both roles, and new entities need no schema change.
- Good, because no response bodies are stored, so a second copy of a minor's data does not exist.
- Good, because the key and the effect cannot disagree, since they share a transaction.
- Good, because the rule is enforced by the build, so a new endpoint cannot silently skip it.
- Bad, because a replay shows the entity's current state, which can differ from the original response.
- Bad, because after 24 hours a repeated key counts as a new request. An offline queue that sends later than that would need a longer expiry.
- Neutral, because the existing `task.idempotency_keys` and the wallet's own handling stay as they are until a follow-up migrates them.

## Pros and Cons of the Options

### Option A: a unique key column on each entity table

- Good: the key and the entity are one row and cannot disagree.
- Bad: it puts a transport concern into every domain table.
- Bad: the key never expires.
- Bad: it needs a new column and migration for each entity.

### Option B: client-chosen entity ids

- Good: the primary key gives idempotency with no extra storage.
- Bad: it differs from the header convention the other endpoints use.
- Bad: clients choose database identifiers, and a collision across users needs special handling.
- Bad: payload mismatches still need their own check.

### Option C: content-based deduplication

- Good: it needs no client cooperation.
- Bad: a parent can legitimately create the same chore twice, so it rejects valid requests.

### Option D: a universal filter that stores and replays whole responses

- Good: no per-endpoint code, and the replay is byte-identical.
- Bad: the claim and the effect must share one transaction, which means one transaction per HTTP request. That conflicts with service-owned transactions, retries and events published inside them, and S3 calls that must stay outside a transaction.
- Bad: it stores bodies that contain minors' data.
- Bad: external side effects such as uploads and pushes are still not covered.

### Option E: one generic platform table plus a policy enforced by a test

- Good: see the consequences above.
- Bad: each owning service still supplies the effect and the reload, so it is not fully automatic.

### Option F: client-side guard only

- Good: trivial.
- Bad: it cannot cover a lost response, an app killed mid-request, or a second attempt after an error. It stays as a second layer in the app, not as the fix.

## More Information

- Existing guidance: the "Idempotency" section of the backend's `docs/transactions.md` allows either a keys table or a unique column on the effect table. This ADR picks the table and makes it generic.
- Implemented on `feat/idempotency-keys`: the `platform.idempotency_keys` table, the claim mechanism, the cleanup ticker, `POST /tasks`, `/goals` and `/task-series` with a required key, `@NaturallyIdempotent` on approve, reject, request-redo, cancel, goal close, payout fulfil, cancel and decline, `/links/join` and `/auth/google`, the architecture test `IdempotencyPolicyTests`, and `platform` in the table-ownership test.
- Follow-ups: move submit off `task.idempotency_keys` onto the new table; fold the wallet's handling of goal lock and unlock and of payouts in.
- Delivery: backend branch for the mechanism first. The app's create-task form depends on it, because the app generates the key when the form opens and reuses it for identical retries.
- Revisit partitioning only if traffic reaches tens of millions of requests per day.
