# API reference

Everything the backend exposes today, read from the code (controllers, DTO records, services, domain rules). Staging base URL: `https://api-staging.tiyn.dev`. Module and use-case background lives in `docs/modules.md` and `docs/use-cases.md`; this file is the contract a client builds against.

## Conventions

- **Transport:** JSON over HTTPS. Timestamps are ISO-8601 UTC instants (`2026-11-01T20:58:13.173252Z`). Ids are UUIDs.
- **Auth:** every endpoint except `POST /auth/google` and `GET /actuator/health/**` needs `Authorization: Bearer <token>`. The token is our own JWT (HS256, issuer `tiyn`, `sub` = user id, `role` = `PARENT` or `TEEN`), valid for `JWT_TTL` (default 7 days). There is no refresh or logout endpoint: when it expires, sign in with Google again.
- **Money:** `long` in **minor units** (tiyn): 1 KZT = 100. Currency is always `"KZT"`. Examples: reward `150000` = 1,500 KZT. Defaults: maximum reward or penalty `10000000` (100,000 KZT, `TASK_MAX_REWARD_AMOUNT`), minimum payout `10000` (100 KZT, `LEDGER_MIN_PAYOUT_AMOUNT`).
- **Errors:** every failure is `{"error": "<code>"}` with an HTTP status. Clients switch on the code; the text is never localised. Cross-cutting codes:

| Status | Code | When |
|---|---|---|
| 400 | `invalid_body` | unreadable or malformed JSON |
| 400 | `invalid_request` | missing parameter or part, malformed path value |
| 401 | `unauthenticated` | missing, expired or invalid token |
| 403 | `forbidden` | wrong role, or not your resource |
| 404 | `not_found` | unknown route or resource |
| 405 / 415 | `method_not_allowed` / `unsupported_media_type` | |
| 413 | `payload_too_large` | multipart above 8 MB |
| 422 | `validation_failed` | bean validation failure |
| 500 | `internal_error` | unexpected; retry is safe only for idempotent calls |

- **Idempotency:** `Idempotency-Key` (a non-blank string, at most 100 characters) is **required** on `POST /tasks`, `POST /task-series`, `POST /goals`, `POST /tasks/{id}/submit`, `POST /goals/{id}/lock`, `POST /goals/{id}/unlock` and `POST /payouts`, and rejected with 422 `invalid_idempotency_key` when missing. Repeating a call with the same key returns the original result instead of repeating the effect; a repeated create (`POST /tasks`, `/task-series`, `/goals`) returns `201` with the object created the first time, in its current state. Keys are scoped to the caller and expire after 24 hours. Reusing a key with a different payload is 422 `invalid_idempotency_key`. Generate one UUID per user action and reuse it on retries. Approve, reject, request-redo, cancel, `POST /links/join`, `POST /me/join-code/regenerate` and `POST /auth/google` need no key: repeating them is safe, except that repeating request-redo returns 409 `invalid_transition` (the task is already back in `ASSIGNED`; reload it) and repeating a regenerate issues yet another code (see its section).
- **Eventual consistency:** a rewarded approval and an expired penalty reach the wallet through events, usually within seconds. After `approve`, the balance may lag; re-read it instead of assuming.
- **Roles:** `PARENT` and `TEEN`. A teen may be linked to several parents; a parent to several teens. A parent only sees and acts on tasks they created for linked teens.
- **Lists that grow are paginated** with an opaque cursor, always newest first; see [Pagination](#pagination). `GET /family` and `GET /tasks/{id}/proofs` stay plain because the domain keeps them small.

## Pagination

`GET /tasks`, `GET /payouts`, `GET /goals`, `GET /task-series` and `GET /wallet/entries` are paginated. Anything that grows with time is. `GET /family` (a handful of people) and `GET /tasks/{id}/proofs` (one entry per submission attempt) return everything and are not paginated. `GET /dashboard` is a composed view with its own window and is not a list. `GET /parent/dashboard` lists the parent's teens, a handful, each with a preview capped at 10 review items.

**Request.** Two optional query parameters:
- `limit`: items per page, default 50. Below 1, above 100 or not a number is 400 `invalid_request`.
- `cursor`: the `nextCursor` of the previous page. Leave it out (or send it empty) for the first page.

**Response.**
```json
{ "items": [ { "id": "…" } ], "nextCursor": "MXwyMDI2LTExLTA3VDEwOjE1OjMwLjEyMzQ1Nlp8…" }
```
`nextCursor` is present only when more items exist and is **omitted on the last page**; that is the only way to know a list has ended. An empty list is `{ "items": [] }`. There is no total count and no page number. A page holds `limit` items except the last, so keep requesting with `nextCursor` until it is absent.

**Order.** Each endpoint has one fixed order, newest first, and the client cannot change it. Rows with the same timestamp are ordered by `id` descending, so no row is skipped or repeated across a page boundary.

| Endpoint | Newest first by | Filters (a cursor is valid only with the filters it was issued for) |
|---|---|---|
| `GET /tasks` | `created_at`, then `id` | `teenId` (parent), `state` |
| `GET /payouts` | `requested_at`, then `id` | `state` |
| `GET /goals` | `created_at`, then `id` | none |
| `GET /task-series` | `created_at`, then `id` | `teenId`, `active` |

**Cursor.** Treat it as an opaque string: do not parse, build or store it. It identifies the last item you received and the filters of that request. Pages are stable while rows are added: new items appear at the top on the next refresh from the first page and never shift or duplicate the page you are on. Changing a filter means starting again without a cursor. A cursor that is malformed, from an unknown format version, or used with different filters than it was issued for is 422 `invalid_cursor`; drop it and restart from the first page. A cursor never exposes other users' data: every page is scoped to the caller.

**Cursors are for browsing, not for sync.** A row carries the time its transaction started, not the time it committed, so a row can appear with an older timestamp than rows you already have. Refreshing from the first page picks it up; asking for "everything after X" with a cursor would miss it. Do not use cursors to mirror the server.

## Identity

### `POST /auth/google` (public)

Exchange a Google ID token for our token. The token's audience must be one of `GOOGLE_CLIENT_IDS`.

Request:
```json
{ "idToken": "<google id token>", "role": "PARENT", "displayName": "Aigerim" }
```
- `role`: **optional**; required only when the account doesn't exist yet. `PARENT` or `TEEN`, upper-case, **only used the first time** an account signs in. An existing account keeps its role and name, and the fields are ignored.

Client flow: call without `role`. On `422 role_required` (the account is new and nothing was created), ask the user for their role and call again with the same `idToken` and the `role`.
- `displayName`: optional, at most 100 characters; falls back to the Google profile name. Normalised like `PATCH /me` (below) before it is stored.

Response `200`:
```json
{ "token": "<our jwt>", "user": { "id": "…", "role": "PARENT", "displayName": "Aigerim", "joinCode": "SXVPUR" } }
```
`joinCode` appears only for parents (six characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`) and changes only when the parent calls `POST /me/join-code/regenerate`. One Google account is one user with one role.

Errors: 401 `invalid_identity` (bad or wrong-audience token), 422 `role_required` (new account, no `role`), 422 `invalid_role`, 422 `invalid_display_name`, 409 `conflict`, 503 `identity_provider_unavailable`.

### `GET /me`

Returns the same `user` object. 401 `unauthenticated` if the user no longer exists.

### `PATCH /me` (parent or teen)

Edit the caller's profile. Partial update: a field that is absent (or `null`) stays as it is, so `{}` is a harmless no-op that returns the current profile.

Request:
```json
{ "displayName": "Aigerim" }
```
- `displayName`: the only editable field. The name is shown to the people the caller is linked to (`GET /family`, `POST /links/join`), so it is normalised before it is stored, the same way as at sign-in: control characters, invisible format characters (zero-width characters, byte-order mark) and Unicode bidi override, embedding and isolate characters are removed, every run of whitespace becomes one space, and the ends are trimmed. The result must be 1–100 characters; the length is checked after normalising. The response carries the stored value, which can differ from what was sent. Names stored before this rule are not rewritten.
- `role`, `joinCode`, the Google subject and `id` cannot be changed. Sending them, or any other field, is 400 `invalid_body`. There is no email field (none is stored). The push-notification language is a property of the device, not the account: change it with `PUT /devices`.

Response `200`: the same `user` object as `GET /me`.

Repeating the call with the same body gives the same result, so it needs no `Idempotency-Key`. Two simultaneous edits are serialised on the user row; the last one to commit wins.

Errors: 400 `invalid_body` (malformed JSON or an unknown field), 401 `unauthenticated` (no token, or the user no longer exists), 422 `invalid_display_name` (blank or empty after normalising, or longer than 100 characters).

### `POST /me/join-code/regenerate` (parent)

Issues a new join code for the caller and invalidates the old one immediately. Use it when the code has leaked or was shared too widely.

No request body. Response `200`: the same `user` object as `GET /me`, carrying the new `joinCode`.
The old code then behaves like any unknown code on `POST /links/join` (422 `invalid_join_code`). Existing family links are untouched: linked teens stay linked and no event is published. A teen who has not joined yet needs the new code.
Needs no `Idempotency-Key`, but repeating a call rotates the code again; each response carries the code that was valid when it was produced, and `GET /me` always returns the current one. After a lost response, read `GET /me` instead of retrying. Two simultaneous calls leave exactly one valid code (the later commit wins).
Errors: 401 `unauthenticated`, 403 `forbidden` (caller is not a parent), 409 `conflict` (the random draw collided with another parent's code five times in a row; try again).

### `POST /links/join` (teen)

Link the teen to a parent by the parent's code. Case and surrounding spaces are ignored. Re-joining after an unlink works.

Request `{ "code": "SXVPUR" }`. Response `200`: `{ "parent": { "id": "…", "displayName": "Aigerim" } }`. Keep the parent `id`: payouts are addressed to it.
Every call counts as an attempt per caller: after 5 attempts within 15 minutes the caller gets 429 `join_rate_limited` (even for a correct code) until the 15-minute window from the first attempt elapses. A successful join resets the counter. The counter is stored in Postgres, so it holds across replicas; it is per account, not per IP.
Errors: 422 `invalid_join_code`, 403 `forbidden` (caller is not a teen), 429 `join_rate_limited`, 409 `conflict`.

### `GET /family`

The people the caller is linked to right now, ordered by display name. A parent gets their teens, a teen gets their parents. This is how a parent learns the `teenId` needed to create the first task, and how a teen learns the `parentId` for payouts. Unlinked people are not listed.

Response `200`:
```json
{ "members": [ { "id": "…", "role": "TEEN", "displayName": "Dana" } ] }
```
`members` is empty when nobody is linked. A client should call it after sign-in and after joining or unlinking.

### `DELETE /links/{teenId}` (parent)

Unlink a teen. `204`. Effects, asynchronous: the parent's open tasks for that teen are cancelled, their recurring series close, an open payout to that parent is cancelled (reason `UNLINKED`), and both sides are notified.
Errors: 400 `invalid_teen_id`, 404 `not_found` (no such link), 403 `forbidden` (caller is not a parent).

## Tasks

A task has a state machine: `ASSIGNED` → `SUBMITTED` → `APPROVED` or `REJECTED`; `SUBMITTED` → `ASSIGNED` again on a redo request; `ASSIGNED` or `SUBMITTED` → `CANCELLED`; `ASSIGNED` → `EXPIRED` when the deadline passes (a ticker runs about once a minute). `kind` is `TASK` or `MILESTONE`. A milestone is itself a task with the same states that groups child tasks through `milestoneId`.

`TaskResponse` (fields that are null are omitted):
```json
{
  "id": "…", "teenId": "…", "parentId": "…", "kind": "TASK",
  "title": "Wash dishes", "description": "", "rewardAmount": 150000, "penaltyAmount": 0,
  "currency": "KZT", "state": "ASSIGNED", "deadlineAt": "2026-11-03T18:00:00Z",
  "submittedAt": null, "decidedAt": null, "decisionNote": null,
  "milestoneId": null, "seriesId": null, "approvedTasks": null, "totalTasks": null
}
```
`approvedTasks` and `totalTasks` appear only on a milestone returned by `GET /tasks/{id}`. `seriesId` appears only on a task that a recurring series created (see `GET /task-series/{id}`) and is omitted on every other task.

### `POST /tasks` (parent) → `201 TaskResponse`

Header `Idempotency-Key` required.

```json
{ "teenId": "…", "kind": "TASK", "title": "…", "description": "…", "rewardAmount": 150000,
  "penaltyAmount": 0, "currency": "KZT", "deadlineAt": "2026-11-03T18:00:00Z", "milestoneId": null }
```
- `teenId` required and must be linked. `kind` defaults to `TASK`. `title` 1–200 characters after trimming. `currency` must be `KZT`. `rewardAmount` > 0 and at most the maximum; `penaltyAmount` 0 up to the maximum (taken from the wallet on expiry). `deadlineAt` must be in the future.
- `milestoneId` puts the task under an open milestone of the same family; a milestone cannot nest.
- Errors: 403 `forbidden`, 422 `teen_required`, `invalid_kind`, `invalid_title`, `invalid_reward`, `invalid_penalty`, `invalid_deadline`, `invalid_milestone`, `invalid_idempotency_key`.

### `GET /tasks`

Teen: their own tasks. Parent: tasks they created, optionally `?teenId=<uuid>`. Both accept `?state=ASSIGNED|SUBMITTED|APPROVED|REJECTED|CANCELLED|EXPIRED`. Returns a page of `TaskResponse` (`{ "items": [...], "nextCursor": "…" }`), newest first by creation time; see [Pagination](#pagination). A parent sees only tasks for teens who are linked to them now. Unknown state: 422 `invalid_filter`; bad `limit`: 400 `invalid_request`; bad `cursor`: 422 `invalid_cursor`.

### `GET /tasks/{id}` → `TaskResponse`

Teen: own task. Parent: own task while the link is active. Others: 403 `forbidden`, unknown id: 404 `not_found`.

### `POST /tasks/{id}/submit` (teen) → `TaskResponse` with state `SUBMITTED`

Header `Idempotency-Key` required. Two forms:
- **Text:** `Content-Type: application/json`, body `{ "text": "…" }`, at most 2000 characters.
- **Image:** `multipart/form-data` with part `file`, JPEG or PNG only, at most 5 MB (the request limit is 8 MB). The server strips EXIF (including GPS), applies the orientation, and re-encodes. Pixel limit 40 million.

Only an `ASSIGNED` task before its deadline can be submitted. Errors: 409 `deadline_passed`, 409 `invalid_transition`, 422 `unsupported_proof` (wrong type, too large, empty or over-long text), 422 `invalid_idempotency_key`, 403 `forbidden`, 413 `payload_too_large`.

### Decisions (parent), all return `TaskResponse`

| Endpoint | Body | Effect |
|---|---|---|
| `POST /tasks/{id}/approve` | none | `SUBMITTED` → `APPROVED`; the reward is credited to the teen's wallet. Repeating it as the same parent is a no-op. |
| `POST /tasks/{id}/reject` | optional `{ "note": "…" }` (≤ 500) | `SUBMITTED` → `REJECTED`; final. |
| `POST /tasks/{id}/request-redo` | optional `{ "note": "…", "deadlineAt": "…" }` | `SUBMITTED` → `ASSIGNED`. A new deadline must be in the future and inside the milestone's bounds; without one, the existing deadline must still be in the future. |
| `POST /tasks/{id}/cancel` | none | `ASSIGNED` or `SUBMITTED` → `CANCELLED`; cancelling a milestone cancels its open children. |

Errors: 409 `invalid_transition`, 409 `concurrent_modification` (retry), 422 `invalid_note`, 422 `invalid_deadline`, 403 `forbidden`.

### `GET /tasks/{id}/proofs` (parent or the teen who owns the task) → `ProofResponse[]`

```json
[ { "attemptNo": 1, "imageUrl": "https://s3-staging.tiyn.dev/tiyn-proofs/proofs/…?X-Amz-…", "text": null, "createdAt": "…" } ]
```
One entry per submission attempt. `imageUrl` is a presigned URL that stops working after `S3_VIEW_URL_TTL` (default 10 minutes); fetch it right away and do not store it. The task's parent (still linked to the teen) and the assigned teen can read; any other caller gets 403 `forbidden` and an unknown task is 404 `not_found`, as for `GET /tasks/{id}`. A task with no submissions returns `[]`.

## Recurring tasks

### `POST /task-series` (parent) → `201`

Header `Idempotency-Key` required.

```json
{ "teenId": "…", "title": "…", "description": "…", "rewardAmount": 50000, "penaltyAmount": 0,
  "currency": "KZT", "recurrence": "DAILY", "timezone": "Asia/Almaty", "dueTime": "18:00:00", "startsOn": "2026-11-02" }
```
- `recurrence`: `DAILY` or `WEEKLY` (weekly repeats on `startsOn`'s weekday). `timezone` defaults to `Asia/Almaty`. `dueTime` is required. `startsOn` defaults to today in that zone and cannot be earlier.
- Each occurrence becomes an ordinary task, created by a ticker; the first is created immediately when due.
- Response: `{ id, teenId, title, description, rewardAmount, penaltyAmount, currency, recurrence, timezone, dueTime, startsOn, active, closed }`.
- Errors: 403 `forbidden`, 422 `teen_required`, `invalid_schedule`, plus the title and reward errors above.

### `GET /task-series` (parent) → page of `SeriesResponse`

The series the caller created, newest first by creation time, paginated (see [Pagination](#pagination)). Each item has the shape `POST /task-series` returns.
- `?teenId=<uuid>`: only that teen's series. The teen must be linked to the caller right now, otherwise 403 `forbidden`.
- `?active=true|false`: only running or only stopped (paused or closed) series. Anything else is 400 `invalid_request`.
- `?limit=` and `?cursor=` as described under Pagination.

Without `teenId` the list also contains stopped series of teens who were unlinked since. Teens get 403 `forbidden`. Each task a series created carries its `seriesId`.

### `GET /task-series/{id}` (parent) → `SeriesResponse`

One series. 404 `not_found` when the id is unknown or belongs to another parent; teens get 403 `forbidden`.

### `PATCH /task-series/{id}` (parent) → `200` `SeriesResponse`

Edits a series or pauses and resumes it. No `Idempotency-Key`: every field is an absolute value, so repeating a call is safe.

```json
{ "title": "…", "description": "…", "rewardAmount": 60000, "penaltyAmount": 0,
  "dueTime": "19:00:00", "timezone": "Asia/Almaty", "active": false }
```
- Every field is optional. An absent or `null` field is left unchanged; send `"description": ""` to clear the description. An empty body `{}` changes nothing and returns the series.
- `active: false` pauses, `active: true` resumes. Pausing a paused series, or resuming a running one, is a no-op. A series can be edited and paused in one call.
- Changes apply to **future occurrences only**. Tasks already created keep their values, including today's occurrence if the ticker has already created it. A paused series creates nothing. **Resuming does not backfill**: days skipped while paused stay skipped, and the next tick creates only the current day's occurrence, and only while its due time is still ahead.
- `recurrence`, `startsOn`, `teenId` and `currency` cannot be changed; sending them is 400 `invalid_body`. To change them, close the series and create a new one.
- Values are validated exactly as on `POST /task-series`: the title, description, reward and penalty rules, and `invalid_schedule` for an unknown `timezone` (a blank one means the default `Asia/Almaty`). A rejected call changes nothing. A pause-only call skips the content rules, so a series can always be paused.
- `SeriesResponse` now also carries `closed` (`true` after `DELETE` or an unlink). A paused series is `active: false, closed: false`.
- Errors: 400 `invalid_body` (unreadable body, unknown field, bad time), 403 `forbidden` (teen, another parent's series, or a parent no longer linked to the teen), 404 `not_found`, 409 `invalid_transition` (the series is closed), 409 `concurrent_modification` (lost a race five times in a row; reload and retry), 422 `invalid_schedule`, `invalid_title`, `invalid_reward`, `invalid_penalty`.

### `DELETE /task-series/{id}` (parent) → `204`

Closes the series for good: stops new occurrences, existing tasks stay, and it cannot be resumed or edited afterwards. Repeatable. 404 `not_found`, 403 `forbidden` for another parent's series.

## Wallet (teen)

`balance` is what the ledger holds, `locked` is the part reserved for goals, and `available = balance − locked − the amount of an open payout request`. A penalty can make the balance negative.

### `GET /balance`
```json
{ "balanceAmount": 150000, "lockedAmount": 50000, "availableAmount": 100000, "currency": "KZT" }
```
Parents get 403 `forbidden`.

### `GET /wallet/entries`

The wallet history: why the balance changed, newest first by creation time, paginated (see [Pagination](#pagination)). Teen: their own entries. Parent: `?teenId=<uuid>` is required and the teen must be linked to the caller right now.
```json
{ "items": [ { "id": "…", "kind": "TASK_REWARD", "amount": 50000, "currency": "KZT",
               "createdAt": "…", "taskId": "…" } ], "nextCursor": "…" }
```
- `kind`: `TASK_REWARD`, `PENALTY`, `PAYOUT`, `GOAL_LOCK` or `GOAL_UNLOCK`.
- `amount` is the signed effect on `availableAmount`: positive for a reward and a goal unlock, negative for a penalty, a payout and a goal lock. A goal lock or unlock moves money between available and locked, so the balance itself does not change; `kind` tells the two apart.
- Exactly one related id is present when the entry has one: `taskId` (reward, penalty), `payoutId` (payout) or `goalId` (goal lock and unlock). Otherwise the field is omitted.
- Payout requests that are still open are not entries; the debit appears when the parent fulfils.
- Errors: 403 `forbidden` (a teen naming another teen, a parent naming an unlinked teen), 422 `teen_required` (parent without `teenId`), 400 `invalid_request` (bad `limit` or `teenId`), 422 `invalid_cursor`.

### Savings goals

`GoalResponse`: `{ id, name, targetAmount, savedAmount, currency, state }` with `state` `ACTIVE` or `CLOSED`. Saving into a goal moves money from available to locked; it does not leave the wallet.

| Endpoint | Body | Notes |
|---|---|---|
| `POST /goals` → `201` | `{ "name": "Bike", "targetAmount": 2000000 }` + `Idempotency-Key` | name 1–100 characters, target > 0 |
| `GET /goals` | | page of the teen's goals, newest first |
| `GET /goals/{id}` | | 404 `not_found` for someone else's goal |
| `POST /goals/{id}/lock` | `{ "amount": 50000 }` + `Idempotency-Key` | saved plus amount must not exceed the target |
| `POST /goals/{id}/unlock` | `{ "amount": 20000 }` + `Idempotency-Key` | cannot unlock more than saved |
| `POST /goals/{id}/close` | none | releases everything saved; repeating it is a no-op |

Errors: 422 `invalid_name`, `invalid_amount`, `exceeds_target`, `insufficient_funds` (not enough available), `insufficient_goal_funds`, `invalid_idempotency_key`; 409 `goal_closed`; 403 `forbidden` (parent).

### Payouts (cash-out requests)

A teen asks a linked parent for money; the parent hands over the cash and confirms. `PayoutResponse`:
```json
{ "id": "…", "teenId": "…", "parentId": "…", "amount": 1000000, "currency": "KZT",
  "state": "REQUESTED", "requestedAt": "…", "expiresAt": "…", "resolvedAt": null, "cancelReason": null }
```
`state`: `REQUESTED`, `FULFILLED` or `CANCELLED`; `cancelReason`: `TEEN_CANCELLED`, `DECLINED`, `EXPIRED` or `UNLINKED`. A teen can have **one** open payout at a time. An open request is also held back from `availableAmount`. Requests expire after `LEDGER_PAYOUT_EXPIRY` (default 7 days). `expiresAt` is `requestedAt` plus that period, computed by the server; use it for a countdown and never derive it on the client. It is the instant the request becomes eligible for expiry, so it is present only while `state` is `REQUESTED` and omitted once the payout is fulfilled or cancelled; the expiry ticker runs about once a minute, so a request can stay open for a short while after `expiresAt`.

| Endpoint | Caller | Notes |
|---|---|---|
| `POST /payouts` → `201` | teen | `{ "parentId": "…", "amount": 1000000 }` + `Idempotency-Key`; amount at least the minimum and no more than available |
| `GET /payouts?state=` | both | page of payouts, newest first by `requestedAt`; teen: own; parent: addressed to them |
| `GET /payouts/{id}` | both | only participants; others get 404 |
| `POST /payouts/{id}/fulfil` | parent | debits the wallet |
| `POST /payouts/{id}/decline` | parent | |
| `POST /payouts/{id}/cancel` | teen | |

Errors: 422 `parent_required`, `invalid_amount`, `amount_too_small`, `insufficient_funds`, `invalid_filter`, `invalid_idempotency_key`; 409 `payout_already_open`, 409 `invalid_transition`; 403 `forbidden` (wrong role, or the link is gone).

## Teen home screen

### `GET /dashboard` (teen only; parents get 403)

One call for the home screen:
```json
{
  "balance": { "balanceAmount": 0, "lockedAmount": 0, "availableAmount": 0, "currency": "KZT" },
  "pendingAmount": 150000,
  "chores": [ { "id": "…", "kind": "TASK", "title": "…", "description": "…", "rewardAmount": 0, "penaltyAmount": 0, "currency": "KZT", "state": "ASSIGNED", "deadlineAt": "…", "milestoneId": null } ],
  "milestones": [ { "…": "same fields, plus approvedTasks and totalTasks" } ],
  "goals": [ { "id": "…", "name": "…", "targetAmount": 0, "savedAmount": 0, "currency": "KZT" } ],
  "openPayout": { "id": "…", "parentId": "…", "amount": 0, "currency": "KZT", "requestedAt": "…", "expiresAt": "…" }
}
```
`pendingAmount` is the sum of rewards of tasks awaiting a parent's decision. `milestoneId` is the id of the milestone a task belongs to, or `null`; the client can use it to group tasks under their milestone. `chores` and `milestones` hold open work plus recently decided tasks (window `DASHBOARD_RECENT_WINDOW`, default 7 days, at most `DASHBOARD_MAX_TASKS` = 50 tasks). `goals` lists active goals. `openPayout` is omitted when there is none.

## Parent home screen

### `GET /parent/dashboard` (parent only; teens get 403)

One call for the parent's home screen, covering every linked teen:
```json
{
  "totalPendingReviews": 3,
  "totalOpenPayouts": 1,
  "teens": [
    {
      "id": "…",
      "displayName": "Dana",
      "balance": { "balanceAmount": 0, "lockedAmount": 0, "availableAmount": 0, "currency": "KZT" },
      "pendingReviewCount": 12,
      "pendingReviews": [ { "id": "…", "title": "…", "rewardAmount": 0, "currency": "KZT", "submittedAt": "…" } ],
      "openPayout": { "id": "…", "amount": 0, "currency": "KZT", "requestedAt": "…" }
    }
  ]
}
```
`teens` holds the teens linked to the caller, ordered by display name; a parent with none gets an empty list. It is not paginated: a parent is linked to a handful of teens. `pendingReviews` are the caller's own tasks in state `SUBMITTED` for that teen, newest `submittedAt` first, and at most 10 per teen (`DASHBOARD_REVIEW_PREVIEW`); `pendingReviewCount` is the true number, so a client shows "12 waiting" and opens `GET /tasks?state=SUBMITTED&teenId=…` for the rest. `openPayout` is the teen's open request addressed to the caller and is omitted when there is none. `totalPendingReviews` and `totalOpenPayouts` sum across all teens (counts, not capped by the preview). A teen removed with `DELETE /links/{teenId}` disappears from the response at once.

## Push notifications

### `PUT /devices` → `204`
Register or refresh a device: `{ "token": "<fcm token>", "platform": "ANDROID", "locale": "kk" }`. `token` is at most 4096 characters; `platform` is `ANDROID` or `IOS` (case-insensitive); `locale` is `ru` or `kk`. Repeating it with the same token updates the record. Errors: 422 `invalid_token`, `invalid_platform`, `invalid_locale`.

### `DELETE /devices` → `204`
`{ "token": "…" }`. Call it on sign-out. Tokens that Firebase reports as dead are removed by the server.

### What a push contains
A localised `notification` (title and body in Russian or Kazakh by the device's locale) plus a `data` map of strings: `type` always, and `taskId` or `payoutId` when relevant, for deep links. `taskId` is always the id of the task the event is about; for a milestone's own push (assigned, submitted, approved, …) it is the milestone's id, and a child task's push carries the child's id. Open the milestone through `GET /tasks/{id}`. `type` is one of:

| Recipient | Types |
|---|---|
| teen | `TASK_ASSIGNED`, `TASK_APPROVED`, `TASK_REJECTED`, `TASK_REDO_REQUESTED`, `TASK_CANCELLED`, `TASK_EXPIRED`, `TASK_EXPIRED_WITH_PENALTY`, `PENALTY_APPLIED`, `PAYOUT_FULFILLED`, `PAYOUT_DECLINED`, `PAYOUT_EXPIRED`, `PAYOUT_UNLINKED`, `FAMILY_LINKED_TEEN`, `FAMILY_UNLINKED` |
| parent | `TASK_SUBMITTED`, `PAYOUT_REQUESTED` (high priority), `PAYOUT_CANCELLED_BY_TEEN`, `FAMILY_LINKED_PARENT`, `FAMILY_UNLINKED` |

Each event is sent at most once per recipient, even if the server redelivers it. The push is a nudge, not a source of truth: on receipt, refetch the data.

## Operations

`GET /actuator/health/liveness` and `/readiness` are public and return `{"status":"UP"}`. Nothing else under `/actuator` is exposed.

## Gaps a client will hit

These are facts about the current API, listed so they are decided on purpose and not found in the app:

1. **No `If-Modified-Since` or sync.** Lists are paginated, but their cursors are for browsing only (see Pagination). A client that must mirror server state refetches from the first page.
2. **No refresh token.** Sessions last 7 days, then the user signs in with Google again.
3. **No account deletion or data export.** The last matters for the Kazakhstan personal-data law. The profile has one editable field, `displayName` (`PATCH /me`); there is no avatar and no email.
4. **Push copy is Russian and Kazakh only,** with no English fallback; the Kazakh text has not been reviewed by a native speaker.
