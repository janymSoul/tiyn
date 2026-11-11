# API reference

Everything the backend exposes today, read from the code (controllers, DTO records, services, domain rules). Staging base URL: `https://api-staging.tiyn.dev`. Module and use-case background lives in `docs/modules.md` and `docs/use-cases.md`; this file is the contract a client builds against.

## Conventions

- **Transport:** JSON over HTTPS. Timestamps are ISO-8601 UTC instants (`2026-11-01T20:58:13.173252Z`). Ids are UUIDs.
- **Auth:** every endpoint except `POST /auth/google`, `POST /auth/refresh`, `POST /auth/logout` and `GET /actuator/health/**` needs `Authorization: Bearer <token>`. The token is our own JWT (HS256, issuer `tiyn`, `sub` = user id, `role` = `PARENT` or `TEEN`), valid for `JWT_TTL` (default 7 days). Before it expires, or after, exchange the refresh token for a new one with `POST /auth/refresh` (see Identity). Do not send an `Authorization` header to the three public `/auth/*` endpoints: an expired or invalid one is rejected with 401 `unauthenticated` before the endpoint runs.
- **Money:** `long` in **minor units** (tiyn): 1 KZT = 100. Currency is always `"KZT"`. Examples: reward `150000` = 1,500 KZT. Defaults: maximum reward or penalty `10000000` (100,000 KZT, `TASK_MAX_REWARD_AMOUNT`), minimum payout `10000` (100 KZT, `LEDGER_MIN_PAYOUT_AMOUNT`).
- **Errors:** every failure is `{"error": "<code>"}` with an HTTP status. Clients switch on the code; the text is never localised. Cross-cutting codes:

| Status | Code | When |
|---|---|---|
| 400 | `invalid_body` | unreadable or malformed JSON |
| 400 | `invalid_request` | missing parameter or part, malformed path value |
| 401 | `unauthenticated` | missing, expired or invalid token, or the token's account was deleted |
| 403 | `forbidden` | wrong role, or not your resource |
| 404 | `not_found` | unknown route or resource |
| 405 / 415 | `method_not_allowed` / `unsupported_media_type` | |
| 413 | `payload_too_large` | multipart above 8 MB |
| 422 | `validation_failed` | bean validation failure |
| 500 | `internal_error` | unexpected; retry is safe only for idempotent calls |

- **Idempotency:** `Idempotency-Key` (a non-blank string, at most 100 characters) is **required** on `POST /tasks`, `POST /task-series`, `POST /goals`, `POST /tasks/{id}/submit`, `POST /goals/{id}/lock`, `POST /goals/{id}/unlock`, `POST /payouts` and `POST /teens/{teenId}/adjustments`, and rejected with 422 `invalid_idempotency_key` when missing. Repeating a call with the same key returns the original result instead of repeating the effect; a repeated create (`POST /tasks`, `/task-series`, `/goals`) returns `201` with the object created the first time, in its current state. Keys are scoped to the caller and expire after 24 hours. Reusing a key with a different payload is 422 `invalid_idempotency_key`. Generate one UUID per user action and reuse it on retries. Approve, reject, request-redo, cancel, `POST /links/join`, `POST /links/requests/{id}/approve`, `POST /links/requests/{id}/decline`, `POST /me/join-code/regenerate`, `POST /auth/google` and `POST /auth/logout` need no key: repeating them is safe (each sign-in starts a new session), except that repeating request-redo returns 409 `invalid_transition` (the task is already back in `ASSIGNED`; reload it) and repeating a regenerate issues yet another code (see its section). `POST /auth/refresh` takes no key either, and repeating it with the same token is not safe (see Identity).
- **Eventual consistency:** a rewarded approval and an expired penalty reach the wallet through events, usually within seconds. After `approve`, the balance may lag; re-read it instead of assuming.
- **Roles:** `PARENT` and `TEEN`. A teen may be linked to several parents; a parent to several teens. A parent only sees and acts on tasks they created for linked teens.
- **Lists that grow are paginated** with an opaque cursor, always newest first; see [Pagination](#pagination). `GET /family` and `GET /tasks/{id}/proofs` stay plain because the domain keeps them small.

## Pagination

`GET /tasks`, `GET /payouts`, `GET /goals`, `GET /task-series`, `GET /wallet/entries` and `GET /links/requests` are paginated. Anything that grows with time is. `GET /family` (a handful of people) and `GET /tasks/{id}/proofs` (one entry per submission attempt) return everything and are not paginated. `GET /dashboard` is a composed view with its own window and is not a list. `GET /parent/dashboard` lists the parent's teens, a handful, each with a preview capped at 10 review items. `GET /me/export` streams complete collections inside one JSON document and is not paginated.

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
| `GET /links/requests` | `created_at`, then `id` | none (pending requests only) |

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
{ "token": "<our jwt>", "user": { "id": "…", "role": "PARENT", "displayName": "Aigerim", "joinCode": "SXVPUR", "joinApproval": false }, "refreshToken": "<opaque string>" }
```
`joinCode` and `joinApproval` appear only for parents. `joinCode` is six characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` and changes only when the parent calls `POST /me/join-code/regenerate`. `joinApproval` is `false` until the parent turns on [approval of joins](#put-mejoin-approval-parent). One Google account is one user with one role. Every sign-in starts a new session with its own `refreshToken`; sessions on several devices are independent.

Errors: 401 `invalid_identity` (bad or wrong-audience token), 422 `role_required` (new account, no `role`), 422 `invalid_role`, 422 `invalid_display_name`, 409 `conflict`, 503 `identity_provider_unavailable`.

### `POST /auth/refresh` (public)

Exchange a refresh token for a new access token and a new refresh token.

Request: `{ "refreshToken": "<opaque string>" }`

Response `200`: the same shape as `POST /auth/google`. The `role` in the new access token and in `user` is read from the account at this moment.

Errors: 401 `invalid_refresh_token` for every failure, so the response never says why: unknown, malformed, blank or missing token, expired, revoked, or already used.

Refresh flow:
- A refresh token is an opaque random string, valid for `JWT_REFRESH_TTL` (default 30 days from when it was issued) and **single use**. Each successful refresh returns a new `refreshToken`; replace the stored one with it and discard the old one. Each rotation extends the session by another `JWT_REFRESH_TTL`.
- Refresh when the access token is about to expire or after a 401 `unauthenticated`, then retry the original call once. On 401 `invalid_refresh_token`, clear both tokens and sign in with Google again.
- Presenting a token that was already used is treated as theft: the whole session (every token issued since sign-in) is revoked and the answer is 401. Two refreshes with the same token racing each other therefore leave one success and the session revoked. Keep one refresh in flight per session, and if the response to a refresh is lost, sign in again instead of retrying with the old token.
- Store the refresh token in secure storage (Keychain / Keystore). The server keeps only a SHA-256 hash of it and cannot show it again.

### `POST /auth/logout` (public)

Request: `{ "refreshToken": "<opaque string>" }`

Response `204`, always: for a valid, expired, already-used, revoked or unknown token alike, so it never reveals whether the token existed. A known token revokes its whole session (that token and every other token issued since the same sign-in). Repeating it is harmless. Other sessions of the same account are not affected. The access token already issued stays valid until it expires (`JWT_TTL`).

### `GET /me`

Returns the same `user` object. 401 `unauthenticated` if the user no longer exists or was deleted.

### `DELETE /me` (parent or teen)

Delete the caller's own account. No body; `204`. **Irreversible**: there is no grace period and no undo. Export what you need first.

The account is **anonymised, not erased**, because the other family's records (tasks a parent created, payouts, ledger history) refer to the user id and the ledger is append-only. Everything that identifies the person goes in one transaction:

| Removed or replaced | Kept |
|---|---|
| display name becomes `Deleted user` | the user id and the role (so other records still point at "someone") |
| the Google subject is replaced by a tombstone, so the same Google account can sign up again as a brand-new user | tasks, proofs, payouts and ledger entries that reference the id (see below) |
| the parent's join code is released and nothing can use it again | |
| every active family link is removed, exactly as `DELETE /links/{teenId}` would do for each one | |
| every refresh token of the user is deleted, so no session can be refreshed | |
| a teen's pending join requests become `WITHDRAWN`; pending join requests addressed to a parent become `DECLINED` (neither side is notified) | decided join requests (`APPROVED`, `DECLINED`, `WITHDRAWN`) |
| the parent's `joinApproval` setting is reset to `false` | |
| the join-attempt counter of the user | |
| push device tokens (asynchronously, within seconds) | |

Effects, as with unlinking: the other side's open tasks for the pair are cancelled and recurring series closed, and the other side is notified of the unlink. Everything asynchronous is delivered at least once and idempotent.

The access token stops working immediately: any request with it, including a second `DELETE /me`, `PATCH /me`, `POST /me/join-code/regenerate` and `GET /me/export`, is 401 `unauthenticated`. The refresh token stops working too: `POST /auth/refresh` with a token of a deleted account answers the same generic 401 `invalid_refresh_token` as for an unknown token, and `POST /auth/logout` still answers `204`. Signing in again with the same Google account creates a new, empty account with a new id and, for a parent, a new join code. Nothing from the old account comes back.

Preconditions: the call is refused with 409 `account_has_open_payout` while the caller is a teen with an open (`REQUESTED`) payout, or a parent who has an open payout addressed to them. Resolve it first (the teen cancels, or the parent fulfils or declines). Nothing else blocks deletion:
- a **non-zero virtual balance is forfeited**: the wallet and its history stay in the database but nobody can reach them, and nothing is paid out;
- active tasks and series do not block; they are cancelled or closed through the unlink events.

What the former counterpart still sees: payouts of a deleted teen remain in the parent's `GET /payouts` (by id; the payout carries no name), and a deleted parent's payouts remain in the teen's. The deleted person no longer appears in `GET /family` and cannot be linked again. In the counterpart's `GET /me/export` the deleted person stays in `familyLinks` (with `removedAt` set) and in `joinRequests` under the placeholder name `Deleted user`. A deleted parent's join code is dead: `POST /links/join` with it answers the generic 422 `invalid_join_code`.

Not removed yet (follow-up): a deleted teen's submitted proofs (text and stored images) stay in storage until a purge job exists, and are no longer reachable through the API because the link is gone. There is also no hard-purge job for the anonymised rows.

Errors: 401 `unauthenticated` (already deleted or unknown), 409 `account_has_open_payout`.

### `PUT /me/join-approval` (parent)

Choose whether a teen who enters this parent's join code is linked at once or has to be approved first. The default is `false`: the teen is linked immediately, exactly as before.

Request `{ "required": true }`. Response `200`: the updated `user` object. Repeating the call is harmless.
Errors: 403 `forbidden` (caller is a teen), 422 `validation_failed` (`required` missing or not a boolean).

Turning it off does not touch requests that are already pending: the parent can still approve or decline them. Turning it on does not touch existing links.

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
Join requests that are already pending (see [approval of joins](#put-mejoin-approval-parent)) are not touched either: they belong to the parent, not to the code, so the parent can still approve or decline them after a regenerate, and `GET /links/requests` still lists them. Only a new `POST /links/join` needs the new code.
Needs no `Idempotency-Key`, but repeating a call rotates the code again; each response carries the code that was valid when it was produced, and `GET /me` always returns the current one. After a lost response, read `GET /me` instead of retrying. Two simultaneous calls leave exactly one valid code (the later commit wins).
Errors: 401 `unauthenticated`, 403 `forbidden` (caller is not a parent), 409 `conflict` (the random draw collided with another parent's code five times in a row; try again).

### `POST /links/join` (teen)

Link the teen to a parent by the parent's code. Case and surrounding spaces are ignored. Re-joining after an unlink works.

Request `{ "code": "SXVPUR" }`. Response `200`: `{ "parent": { "id": "…", "displayName": "Aigerim" } }`. Keep the parent `id`: payouts are addressed to it.
Every call counts as an attempt per caller: after 5 attempts within 15 minutes the caller gets 429 `join_rate_limited` (even for a correct code) until the 15-minute window from the first attempt elapses. A successful join resets the counter. The counter is stored in Postgres, so it holds across replicas; it is per account, not per IP.
Errors: 422 `invalid_join_code`, 403 `forbidden` (caller is not a teen), 429 `join_rate_limited`, 409 `conflict`.

**When the parent requires approval** (`joinApproval` is `true`) and the teen is not already linked to that parent, nothing is linked. A join request is created and the call answers `202`:
```json
{ "requestId": "…", "status": "PENDING", "parent": { "id": "…", "displayName": "Aigerim" } }
```
The parent is told by push (`JOIN_REQUESTED`, with `requestId` in `data`) and decides with the endpoints below. Asking again while a request is pending returns the same `requestId` with `202` and sends no second push. A teen who is already linked gets the usual `200` no-op. The rate limit and the generic `invalid_join_code` apply exactly as above; a code that resolves to a request resets the attempt counter like a successful join. When the parent approves, both sides get the usual `FamilyLinked` welcome push and the link appears in `GET /family`. Keep the `requestId` if the app wants to offer "withdraw": there is no endpoint that lists a teen's own requests.

### `GET /links/requests` (parent)

The pending join requests addressed to the caller, newest first, [paginated](#pagination).
```json
{ "items": [ { "id": "…", "teenId": "…", "teenDisplayName": "Dana", "createdAt": "2026-11-10T10:00:00Z" } ] }
```
Approved, declined and withdrawn requests are not listed. Errors: 403 `forbidden` (caller is a teen), 422 `invalid_cursor`, 400 `invalid_request`.

### `POST /links/requests/{id}/approve` (parent)

Links the teen to the caller exactly as an immediate join does (including the `FamilyLinked` event and its pushes) and closes the request as `APPROVED`. Response `200`:
```json
{ "requestId": "…", "status": "APPROVED", "teen": { "id": "…", "displayName": "Dana" } }
```
Repeating it returns the same response and changes nothing, even if the parent unlinked the teen in between: an approval never re-links on replay. If the teen already joined directly (approval was turned off meanwhile), the link is not duplicated.
Errors: 403 `forbidden` (not the parent the request is addressed to, or caller is a teen), 404 `not_found`, 409 `invalid_transition` (the request was already declined or withdrawn), 409 `conflict`, 400 `invalid_request` (malformed id).

### `POST /links/requests/{id}/decline` (parent)

Closes the request as `DECLINED`; no link is made. Response `200` with `"status": "DECLINED"` in the same shape as approve. Repeating it is harmless. The teen is not notified and may ask again.
Errors: as approve; 409 `invalid_transition` when the request was already approved or withdrawn.

### `DELETE /links/requests/{id}` (teen)

Withdraws the caller's own pending request. `204`; repeating it is harmless.
Errors: 403 `forbidden` (not the requesting teen), 404 `not_found`, 409 `invalid_transition` (the parent already approved or declined it), 400 `invalid_request`.

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

### Manual bonus or penalty (parent)

> **Proposed feature.** This comes from the maintainers' review, not from `docs/use-cases.md` (which has only task rewards and automatic missed-task penalties). It is documented here so the contract can be reviewed; drop it together with `UC-14` and migration V17 if it is not wanted.

`POST /teens/{teenId}/adjustments` → `201`, parent only, `Idempotency-Key` required.
```json
{ "kind": "BONUS", "amount": 25000, "reason": "Great grades" }
```
`kind` is `BONUS` (credits the teen's wallet) or `PENALTY` (debits it). `amount` is a positive integer in minor units, at most `LEDGER_MAX_ADJUSTMENT_AMOUNT` (default 10000000, the same as the task reward maximum). `reason` is required, 1 to 140 characters after normalisation: every whitespace character (line breaks and tabs included) becomes a plain space, other control and invisible format characters are removed, and the ends are trimmed. Length is counted in characters, not bytes. The response is the new entry:
```json
{ "id": "…", "teenId": "…", "parentId": "…", "kind": "BONUS", "amount": 25000, "currency": "KZT",
  "reason": "Great grades", "createdAt": "…" }
```
- The parent must be linked to the teen right now; the link is re-checked under the wallet lock. An unlinked parent gets 403 `forbidden`, and so does an id that belongs to nobody (the two are deliberately indistinguishable, as for `teenId` on `POST /task-series`).
- A penalty may take the balance below zero, exactly like the automatic missed-task penalty (UC-11): `available` goes negative and blocks payout requests and goal locks until bonuses or rewards pay it down.
- An adjustment is an append-only ledger entry (`MANUAL_BONUS` or `MANUAL_PENALTY`) that records the parent and the reason. There is no edit and no delete. A mistake is corrected with an adjustment of the opposite kind and the same amount.
- The teen gets a push (`BONUS_RECEIVED` or `MANUAL_PENALTY_APPLIED`) with the amount; the reason is not in the push.
- A replay with the same key returns `201` with the same entry and changes nothing. The same key with another teen, kind, amount or reason is 422 `invalid_idempotency_key`.

Errors: 403 `forbidden` (teen caller, parent not linked, unknown teen); 400 `invalid_request` (malformed `teenId`), `invalid_body` (malformed JSON); 422 `invalid_amount` (missing, not positive, or over the maximum), `invalid_kind`, `invalid_reason`, `invalid_idempotency_key`.

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

## Privacy

### `GET /me/export` (any signed-in user)

Everything the system holds about the caller that they may take with them, as one JSON document. The response is a download (`Content-Disposition: attachment; filename="tiyn-export-<yyyy-MM-dd>.json"`, the date is the UTC day of `generatedAt`) with `Cache-Control: no-store`, so a shared device or proxy keeps no copy. It is a read-only `GET`: no `Idempotency-Key`.

**Rate limit.** One export per user per `EXPORT_MIN_INTERVAL` (default 5 minutes, counted from the last accepted request, kept in Postgres so it holds across replicas). A second request inside the window is 429 `export_rate_limited`; wait and retry. A request that was accepted but failed while streaming also uses up the window.

**Streaming.** The body is written while it is read, page by page (`EXPORT_PAGE_SIZE`, default 500 rows), so memory does not grow with the amount of data and the first bytes arrive before the last row is read. Consequences for a client: the status is `200` as soon as the stream starts, so a failure in the middle shows up as a truncated, unparsable file, never as an error status; treat a file that does not parse as a failed export. A client that stops reading is cut off by the server's connection timeout.

**Consistency.** Each section of the document is read in one read-only `REPEATABLE READ` transaction of the module that owns the data, so a section never shows a half-applied change. The sections are not one global snapshot: a payout confirmed between reading `ledgerEntries` and `payouts` can show up in one and not the other. The document says so itself in `consistency`.

Timestamps are ISO-8601 UTC, money is `long` in minor units, fields that are null are omitted. Collections are ordered newest first (`createdAt`, `requestedAt` for payouts, then `id` descending) and complete.

Teen:
```json
{
  "generatedAt": "2026-11-11T10:15:30.123456Z",
  "consistency": "Each section is read in its own read-only snapshot; sections are not one global snapshot.",
  "profile": { "id": "…", "role": "TEEN", "displayName": "Dana", "createdAt": "…" },
  "familyLinks": [ { "id": "…", "displayName": "Aigul", "linkedAt": "…", "removedAt": null } ],
  "joinRequests": [ { "id": "…", "parentId": "…", "parentDisplayName": "Aigul", "state": "PENDING",
                      "createdAt": "…", "decidedAt": null } ],
  "tasks": [ {
    "id": "…", "parentId": "…", "teenId": "…", "milestoneId": null, "seriesId": null, "occurrenceDate": null,
    "kind": "TASK", "title": "…", "description": "…", "rewardAmount": 100000, "penaltyAmount": 0, "currency": "KZT",
    "state": "APPROVED", "deadlineAt": "…", "submittedAt": "…", "decidedAt": "…", "decidedBy": "…", "decisionNote": null,
    "createdAt": "…",
    "proofs": [ { "attemptNo": 1, "text": "done", "hasImage": false, "submittedAt": "…" },
                { "attemptNo": 2, "hasImage": true, "imageUrl": "https://…?X-Amz-…", "submittedAt": "…" } ]
  } ],
  "wallet": { "balanceAmount": 100000, "lockedAmount": 40000, "currency": "KZT" },
  "ledgerEntries": [ { "id": "…", "kind": "TASK_REWARD", "balanceDelta": 100000, "lockedDelta": 0, "currency": "KZT",
                       "refId": "…", "note": null, "actorId": null, "createdAt": "…" } ],
  "savingsGoals": [ { "id": "…", "name": "Headphones", "targetAmount": 900000, "savedAmount": 40000, "currency": "KZT",
                      "state": "ACTIVE", "createdAt": "…" } ],
  "payouts": [ { "id": "…", "teenId": "…", "parentId": "…", "amount": 30000, "currency": "KZT", "state": "REQUESTED",
                 "requestedAt": "…", "resolvedAt": null, "resolvedBy": null, "cancelReason": null } ]
}
```

Parent: `profile` (with `joinCode` and `joinApproval`, the setting of [approval of joins](#put-mejoin-approval-parent)), `familyLinks`, `joinRequests` (the requests addressed to them: `id`, `teenId`, `teenDisplayName`, `state`, `createdAt`, `decidedAt`), `tasks` (the tasks they created, without `proofs`: a proof is the teen's data), `taskSeries` (`id`, `teenId`, `kind`, `title`, `description`, `rewardAmount`, `penaltyAmount`, `currency`, `recurrence`, `timezone`, `dueTime`, `startsOn`, `active`, `createdAt`) and `payouts` addressed to them. A parent has no wallet, ledger entries or goals, so those keys are absent. A user with no data gets the same shape with empty arrays (a teen's `wallet` reads as zeros, like `GET /balance`).

What is in it and what is deliberately not:
- `familyLinks` lists every link ever made, including removed ones (`removedAt` set). The other person appears only as `id` and `displayName`.
- `joinRequests` lists every join request the caller made (teen: `parentId`, `parentDisplayName`) or received (parent: `teenId`, `teenDisplayName`), pending and decided (`APPROVED`, `DECLINED`, `WITHDRAWN`), newest first. The other person appears only as id and display name; `decidedAt` is omitted while the request is pending. `joinApproval` is in the parent's `profile` and absent for a teen.
- A proof with an image carries `hasImage` and `imageUrl`, a short-lived presigned URL (`S3_VIEW_URL_TTL`, default 10 minutes, valid for anyone who holds it). The image bytes and the storage object key are not in the document; download the image from the URL right away or export again after the window.
- Never included: the Google subject (`idp_sub`), session tokens or any JWT data, device push tokens, refresh tokens (their hashes, session families and expiry), join-attempt counters, idempotency keys, and any other user's data beyond the counterpart's `id` and `displayName`. No email is stored, so none is exported. A module that stores more personal data extends its own slice of this document and keeps secrets out.

Errors: 401 `unauthenticated`, 429 `export_rate_limited`.

## Push notifications

### `PUT /devices` → `204`
Register or refresh a device: `{ "token": "<fcm token>", "platform": "ANDROID", "locale": "kk" }`. `token` is at most 4096 characters; `platform` is `ANDROID` or `IOS` (case-insensitive); `locale` is `ru` or `kk`. Repeating it with the same token updates the record. Errors: 422 `invalid_token`, `invalid_platform`, `invalid_locale`.

### `DELETE /devices` → `204`
`{ "token": "…" }`. Call it on sign-out. Tokens that Firebase reports as dead are removed by the server.

### What a push contains
A localised `notification` (title and body in Russian or Kazakh by the device's locale) plus a `data` map of strings: `type` always, and `taskId`, `payoutId` or `entryId` (manual adjustments) when relevant, for deep links. `taskId` is always the id of the task the event is about; for a milestone's own push (assigned, submitted, approved, …) it is the milestone's id, and a child task's push carries the child's id. Open the milestone through `GET /tasks/{id}`. `type` is one of:

| Recipient | Types |
|---|---|
| teen | `TASK_ASSIGNED`, `TASK_APPROVED`, `TASK_REJECTED`, `TASK_REDO_REQUESTED`, `TASK_CANCELLED`, `TASK_EXPIRED`, `TASK_EXPIRED_WITH_PENALTY`, `PENALTY_APPLIED`, `BONUS_RECEIVED`, `MANUAL_PENALTY_APPLIED`, `PAYOUT_FULFILLED`, `PAYOUT_DECLINED`, `PAYOUT_EXPIRED`, `PAYOUT_UNLINKED`, `FAMILY_LINKED_TEEN`, `FAMILY_UNLINKED` |
| parent | `TASK_SUBMITTED`, `PAYOUT_REQUESTED` (high priority), `PAYOUT_CANCELLED_BY_TEEN`, `FAMILY_LINKED_PARENT`, `FAMILY_UNLINKED` |

Each event is sent at most once per recipient, even if the server redelivers it. The push is a nudge, not a source of truth: on receipt, refetch the data.

## Operations

`GET /actuator/health/liveness` and `/readiness` are public and return `{"status":"UP"}`. Nothing else under `/actuator` is exposed.

## Gaps a client will hit

These are facts about the current API, listed so they are decided on purpose and not found in the app:

1. **No `If-Modified-Since` or sync.** Lists are paginated, but their cursors are for browsing only (see Pagination). A client that must mirror server state refetches from the first page.
2. **Access tokens cannot be revoked,** except by deleting the account. They last `JWT_TTL` (7 days) and `POST /auth/logout` only ends the refresh session, so a stolen access token works until it expires. Shortening `JWT_TTL` is safe now that clients can refresh.
3. **Account deletion is immediate and irreversible** (`DELETE /me`): there is no grace period yet. Data export exists (`GET /me/export`). The profile has one editable field, `displayName` (`PATCH /me`); there is no avatar and no email.
4. **Push copy is Russian and Kazakh only,** with no English fallback; the Kazakh text has not been reviewed by a native speaker.
5. **No endpoint lists a teen's own join requests,** and a declined request sends no push. The app keeps the `requestId` from the `202`, or the teen asks again; the full list is in `GET /me/export`.
