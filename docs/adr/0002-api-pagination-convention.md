# ADR-0002: Paginate growing API lists with an opaque keyset cursor

- **Status:** accepted
- **Date:** 2026-11-07
- **Deciders:** self
- **Consulted:** none
- **Informed:** none

## Context and Problem Statement

No list endpoint is paginated. Each one is quietly capped instead. `GET /tasks` returns the newest 200 rows ordered by `created_at` alone, so the 201st task disappears without the client knowing, and rows created in the same instant come back in no fixed order. `GET /payouts` stops at 100 with no tiebreaker. `GET /goals` and `GET /task-series` stop at 100. The app counts milestone progress from `GET /tasks`, so the 200 cap can already give it a wrong number. The planned teen wallet history (`docs/api.md`, "Gaps a client will hit" #2) grows faster than any of these lists, because every reward, penalty, payout and goal move adds an entry.

We need one convention that every growing list follows, that a new endpoint cannot skip, and that the wallet history can be built on from its first version. There is one client, the Flutter app, and it is not released yet.

## Decision Drivers

- A client must never lose rows without knowing it.
- Pages must stay stable while new rows arrive. Every list is newest first, so new rows land on top during browsing.
- Each page should cost one index range scan, whatever its depth.
- The `platform` module may own the protocol but must not learn other modules' tables (`docs/modules.md`).
- A new endpoint must not be able to skip the rule silently (the same stance as ADR-0001).
- A breaking change is cheapest now, before release, and should not break staging when it happens.

## Considered Options

- Option A: offset and limit
- Option B: an opaque keyset cursor in a response envelope (chosen)
- Option C: a keyset cursor in a response header, with the body kept as a bare array
- Option D: server-side snapshots or stored page tokens
- Option E: keep the caps and add sync (`If-Modified-Since` / ETag)

## Decision Outcome

Chosen option: **Option B**, because it is the only option that is stable under inserts and costs one index range scan per page with no server-side state, and the envelope leaves room to add fields to the response.

The convention:

- **Which lists.** A collection that grows with time is paginated: `GET /tasks`, `GET /payouts`, `GET /goals`, `GET /task-series` and the wallet history. A collection that the domain keeps small stays a bare array: `GET /family`, and `GET /tasks/{id}/proofs` (one entry per attempt). A composed view (`GET /dashboard`) keeps its own window and is not a collection.
- **Request.** `?limit=` (default 50, maximum 100; below 1 or above 100 is 400 `invalid_request`) and `?cursor=` (absent for the first page).
- **Response.** `{ "items": [ … ], "nextCursor": "…" }`. `nextCursor` is omitted on the last page. The server fetches `limit + 1` rows to know whether one more page exists. No total count is returned.
- **Order.** Each endpoint has one fixed order, documented in `docs/api.md`. It is a sort key plus `id` as the tiebreaker, both descending (newest first). The client cannot choose the order. A different order is a different endpoint or filter, with its own cursor.
- **Cursor.** It is opaque to the client: a base64url string carrying a format version, the sort-key value and the `id` of the last row returned, and a short hash of the filters the request used (`state`, `teenId`, …). It is not signed, because every query is already scoped to the caller and a forged cursor can only page through data the caller may see anyway. A malformed cursor, an unknown version, or a cursor reused with different filters is 422 `invalid_cursor`.
- **Query.** The seek predicate is a row-value comparison in one direction, `(sort_key, id) < (:sortKey, :id)`, ordered `sort_key DESC, id DESC`, so Postgres serves it from one composite index. jOOQ's `seek` produces it.
- **Indexes.** Every paginated access path has a matching composite index `(<owner column>, <sort key> DESC, id DESC)`. Filters such as `state` are applied on top of it until `EXPLAIN` shows they need their own index.
- **Where the code lives.** The `platform` module (OPEN) owns the protocol: the page request with its limit bounds, the cursor encoding and filter hash, the page envelope, and the `invalid_cursor` error. Each module's store writes its own seek query. Platform has no generic query builder and knows no module's tables.
- **Policy.** An architecture test fails the build when a `GET` handler returns a `List` or array unless it is explicitly marked as a bounded list with a reason, in the same way `IdempotencyPolicyTests` enforces ADR-0001.

The cutover:

1. The app reads both shapes for list calls. A bare array is treated as `items` with no next page. This ships first and needs no backend change.
2. The backend moves every paginated endpoint to the envelope in one change. It is deployed to staging only after step 1 is in the build being tested. There is no period in which the server returns both shapes, and no API versioning.

### Consequences

- Good, because rows are no longer lost silently: a client either reaches the last page or knows that more exist.
- Good, because a page stays stable while new rows are inserted on top, and pages never overlap.
- Good, because a page costs the same at any depth and needs no server-side state.
- Good, because the rule is enforced by the build, so a new list endpoint cannot skip it.
- Bad, because the response shape of four existing endpoints breaks. This is acceptable only because the single client is unreleased and adapts first.
- Bad, because clients cannot jump to page N or show a total. Neither is a need of this app.
- Bad, because each paginated access path needs its own index, which is a migration whenever a new filter becomes hot.
- Neutral, because the 200 and 100 caps go away. The maximum page size of 100 takes their place.

### Cursors are for browsing, not for sync

`created_at` is filled by `DEFAULT now()`, which in Postgres is the start time of the inserting transaction, not its commit time. A transaction that starts before another but commits after it produces a row whose `created_at` is older than rows the client has already seen. For newest-first browsing this is harmless: the late row sits above the client's position and appears on the next refresh from the top. It does mean these cursors **must not** be used for incremental sync ("everything after X"), which would miss such rows silently. If the app ever needs sync, it needs a commit-ordered key (for example a sequence assigned at commit, or an outbox), decided in its own ADR.

## Pros and Cons of the Options

### Option A: offset and limit

- Good: trivial to build and to understand; allows jumping to a page.
- Bad: every list is newest first, so a row inserted between two page requests shifts the next page by one and the client sees a duplicate (or misses a row after a delete).
- Bad: deep pages scan and discard the skipped rows. That is not a problem at current sizes, but the instability above is.

### Option B: an opaque keyset cursor in a response envelope

- Good: see the consequences above.
- Bad: breaks the shape of existing list responses once.

### Option C: a keyset cursor in a response header, with the body kept as a bare array

- Good: backward-compatible; no cutover.
- Bad: headers are easy to lose in client interceptors and caches, and the cursor is invisible in logs and API docs that show bodies.
- Bad: there is nowhere to add response fields later without the breaking change this option avoids.

### Option D: server-side snapshots or stored page tokens

- Good: a fully consistent view across pages.
- Bad: per-query state on the server, with expiry and cleanup, for consistency this app does not need.

### Option E: keep the caps and add sync (`If-Modified-Since` / ETag)

- Good: saves bandwidth on refresh.
- Bad: it solves freshness, not size. Rows past the cap are still lost. Push plus refetch already covers freshness for the MVP.

## More Information

- The current caps: `TaskStore.list` (200), `PayoutStore` (`LIST_LIMIT = 100`), `GoalStore` (100), `SeriesStore` (100).
- Existing indexes that a paginated query cannot use as-is: `tasks_teen_state_idx (teen_id, state)`, and nothing on `tasks.parent_id` except the partial `tasks_parent_submitted_idx`. `ledger_entries_teen_created_idx (teen_id, created_at DESC)` lacks `id`.
- Follow-ups: the wallet history is built on this convention from the start; a `GET /tasks` order by deadline for open work, if the app needs it, is a separate endpoint or filter with its own cursor.
