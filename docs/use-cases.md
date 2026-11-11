# Use Cases

Basic MVP use cases derived from the PRD (§3 scenarios, §4 requirements), each mapped to what the system must do to support it. Module names, events, and the FSM follow `docs/modules.md` and `docs/events-outbox.md`; this doc does not redefine them. Items marked **Open** still need a decision.

Actors: **Parent**, **Teen**, **System** (scheduler, event listeners, push).

| ID | Use case | PRD refs | Priority |
|---|---|---|---|
| UC-01 | Sign in and link a teen to a family | PAR-01 | MVP |
| UC-02 | Register a device for push | SYS-03 | MVP |
| UC-03 | Parent creates a task or milestone | PAR-02 | MVP |
| UC-04 | Teen submits proof | TEN-02, SYS-02 | MVP |
| UC-05 | Parent reviews proof (approve / reject / redo) | PAR-03 | MVP |
| UC-06 | Parent cancels a task | PAR-02 | MVP |
| UC-07 | Teen views balance and tasks | TEN-01, TEN-02 | MVP |
| UC-08 | Teen requests a payout | TEN-03 | MVP |
| UC-09 | Parent fulfils a payout through a bank app | SYS-04 | MVP |
| UC-10 | Recurring tasks and deadlines (system-driven) | PAR-02 | MVP |
| UC-11 | A missed task costs money | PAR-04 | Medium |
| UC-12 | Teen sets a savings goal | TEN-04 | Medium |
| UC-13 | User deletes their account | privacy | MVP |
| UC-14 | Parent adds a manual bonus or penalty (proposed) | none | Proposed |

XP/levels (GAM-01) are out of scope here; they would be a sixth module reacting to `TaskApproved`.

---

## UC-01 Sign in and link a teen to a family

**Flow.**
1. Parent signs in with Google without a role; if the account is new, identity answers `role_required` and creates nothing, and the app asks for the role and signs in again with the same token and `PARENT`; identity verifies the Google ID token and issues the app JWT (first sign-in creates the account and its join code, permanent until the parent regenerates it). An account has exactly one role.
2. Parent shares their join code with the teen (shown in the app; sent in a messenger, SMS, or told in person).
3. Teen signs in with Google (first sign-in creates the teen account) and enters the code.
4. Identity finds the parent by code and creates the `family_links` row. If the parent turned on join approval (`PUT /me/join-approval`, off by default) and the teen is not yet linked to them, identity creates a pending join request instead, publishes `JoinRequested` (the parent gets a push) and answers `202`; the parent approves or declines it, and approval creates the link as in this step.
5. `FamilyLinked(parentId, teenId, generation)` is published. The API for these steps is in `docs/modules.md` ("HTTP API").

**Alternates.** Unknown code → generic "invalid code". Repeated failures → 429 `join_rate_limited` after 5 attempts per account within 15 minutes (counter in `identity.join_attempts`, reset by a successful join). Teen already linked to this parent → no-op (also when approval is on). Teen asks again while a request is pending → the same request. Teen withdraws a pending request, or the parent declines it → no link, and the teen may ask again. A teen entering a second parent's code → links to that parent too. Parent removes a link later → teen's tasks are cancelled, wallet history is kept. Parent regenerates the code → the old code is rejected like an unknown one, existing links and pending join requests stay.

**Support needed.**
- identity: Google ID token verification, `users.join_code` (unique, kept until the parent regenerates it, no look-alike characters; join attempts are limited per account by `identity.join_attempts`), `FamilyDirectory.areLinked`, `users.join_approval_required` and `identity.join_requests` for opt-in parent approval.
- ledger consumes the event → creates the teen's wallet (idempotent: the wallet key is `(teen_id, currency)`). notification consumes it → welcome push.

**Open.**
- **Age and consent.** Teens are 11–17. Confirm whether under-14s can hold a Google account in Kazakhstan, and what parental consent the data-protection law requires. Likely needs an ADR.
- **Apple.** Not in MVP: Google only. The provider-agnostic identity design allows adding Sign in with Apple later. Revisit if iOS App Store review requires it.
- **Parent confirmation of a join.** Opt-in, per parent, off by default so existing clients keep working. The join code is reusable until the parent regenerates it, so without approval anyone who learns it can link to that parent; a parent who wants control turns approval on. Not done yet: a teen cannot list their own requests, and a declined request sends no push.

---

## UC-02 Register a device for push

**Flow.** After sign-in the client sends its FCM token and locale; notification stores it. Token refresh upserts; sign-out removes it. A user may have several devices. If the token is already stored under another user (shared phone, sign-out then sign-in), registering it reassigns it to the current user, so pushes never reach the previous user.

**Support needed.** notification owns `device_tokens` (user ID as plain UUID, token, platform, locale). Locale comes from the client, so notification never calls back into identity.

---

## UC-03 Parent creates a task or milestone

**Flow** (Scenario 1, steps 1–3).
1. Parent submits (optionally the milestone this task belongs to,) title, description, reward amount and currency, kind (task/milestone), recurrence (one-time/daily/weekly), deadline, and target teen.
2. Task module checks `FamilyDirectory.areLinked(parent, teen)`.
3. The task is created in `ASSIGNED` (for a recurring task: a series plus the first occurrence).
4. `TaskAssigned` → teen gets a push ("New milestone unlocked").

**Alternates.** Teen not linked → 403. Reward ≤ 0 or above a cap → 422. Deadline in the past → 422. Milestone given but not `ASSIGNED`, itself belonging to a milestone, or the task is recurring or its deadline is later than the milestone's → 422.

**Support needed.**
- task: `tasks`, `task_series`, domain validation; reward stored as integer minor units plus currency (no floats); the UI shows KZT.
- No money is reserved: the ledger is virtual and the parent pays out manually, so the teen's balance is a claim on the parent. UI copy should say so.

---

## UC-04 Teen submits proof

**Flow** (Scenario 1, step 4).
1. Teen opens a task and picks a photo (or enters text). The client compresses the image.
2. Client sends one request to the task API with the proof and an `Idempotency-Key` header.
3. Task service calls `media.Storage.put` (validates type and size, strips EXIF/GPS, stores under a fresh unique key).
4. One transaction: FSM `ASSIGNED → SUBMITTED`, store `task_proofs`, publish `TaskSubmitted`.
5. Parent gets a "proof pending" push.

**Alternates.** Network failure → client retries with the same key; the object is overwritten, the transition is applied once. Teen submits someone else's task → 403. Submit twice → second is a no-op (idempotency) or an invalid-transition error (409). Task already `EXPIRED`/`CANCELLED` → an invalid-transition error (409), clear message. Unsupported type or oversize file → 422.

**Support needed.**
- media: `Storage.put` as in `docs/modules.md`. The teen client never handles S3 keys, so the "submit a key for a file that was never uploaded" hole is closed by design.
- Ordering: upload first, then the transaction (`docs/transactions.md`). Never hold a DB transaction across the S3 call.
- Proof images contain minors' data (an IELTS certificate carries full PII): private bucket, review only via short-lived pre-signed GET URLs, defined retention.
- Request-size limit of the platform applies; the 3 s / 4G target relies on client-side compression.

---

## UC-05 Parent reviews proof

**Flow** (Scenario 1, step 5).
1. Parent opens "Pending Approvals" and views proofs through `presignView` URLs.
2. Chooses one action:
   - **Approve**: `SUBMITTED → APPROVED`, publish `TaskApproved(taskId, teenId, rewardAmount, currency)`.
   - **Reject**: `SUBMITTED → REJECTED` (terminal), `TaskRejected`.
   - **Request redo**: `SUBMITTED → ASSIGNED`, `TaskRedoRequested` with an optional note.
3. Ledger consumes `TaskApproved` and credits the wallet; notification pushes to the teen.

**Alternates.** Concurrent approve (two parents, double-tap) → optimistic version check; the loser gets a concurrent-modification conflict (409). Parent not linked → 403. Replayed command → no-op success.

**As built.** The parent's home screen is one call, `GET /parent/dashboard` (parent only): for every linked teen it returns the balance, the number of proofs waiting for review with a preview of the 10 newest, and the open payout request addressed to that parent, plus totals across teens. See `docs/modules.md` (Dashboard).

**Support needed.** The canonical flow in `docs/transactions.md`. The task transaction writes task tables and publishes the event only; the credit happens in the ledger's own transaction.

**Notes.**
- Eventual consistency: between approval and the ledger listener running (normally milliseconds, longer after a failure) the parent sees "approved" before the teen's balance moves. UI should not promise "immediately credited".
- A redo returns the task to `ASSIGNED`; if the deadline passes meanwhile, it expires like any other assigned task.

---

## UC-06 Parent cancels a task

**Flow.** Parent cancels a task in `ASSIGNED` or `SUBMITTED` (e.g. created by mistake, or the plan changed). FSM → `CANCELLED`, `TaskCancelled` → teen is notified. For a recurring series, the parent chooses "this occurrence" or "this and all future" (the series is closed, so no further occurrences are created). Cancelling a milestone also cancels its open tasks, each notifying the teen.

**Alternates.** Already `APPROVED`/`REJECTED`/`EXPIRED` → an invalid-transition error (409). Cancelling an approved task does not claw back money, and UC-11 covers only missed tasks; the proposed UC-13 would let the parent take the money back by hand.

---

## UC-07 Teen views balance and tasks

**Flow.** Dashboard shows *Balance* and *Available* amounts, *Pending* funds (TEN-01), and tasks grouped into "Daily Chores" and "Major Milestones" (TEN-02). A milestone shows progress computed from its approved tasks ("3 of 5").

**As built.** One call, `GET /dashboard` (teen only), returns the balance (balance, locked, available), pending funds, tasks split into `chores` and `milestones` with progress, active savings goals with their saved amount, and the open payout request if any. Tasks are open work plus those decided in the last 7 days (configurable), capped at 50; older history is `GET /tasks`. See `docs/modules.md` (Dashboard).

**Support needed.**
- ledger: `WalletOverviewReader.overview` → balance, active goals, open payout (one read-only transaction).
- task: `TaskBoardReader.board` → tasks and milestone progress; `PendingReader.pendingReward` → sum of rewards of `SUBMITTED` tasks.
- The `dashboard` module composes them. Ledger never reads task tables.

---

## UC-08 Teen requests a payout

**Flow** (Scenario 2, steps 1–3).
1. Teen enters an amount ≤ available balance and picks the parent to ask (a linked parent). Minimum payout is 100 KZT (configurable).
2. Ledger, in one transaction: lock the wallet `FOR UPDATE`, compute available (approved − open requests − locked), check amount ≤ available, create the `payouts` row `REQUESTED`, write `PayoutRequested`.
3. Parent receives a high-priority push.

Nothing is debited yet; the open request only lowers *available*, so several requests cannot exceed the balance.

**Alternates.** Amount > available, ≤ 0, or below a minimum → 422. Double-submit → idempotency key returns the original payout. Teen cancels their own request → `CANCELLED`. Parent declines → `CANCELLED` with a reason. Unanswered for 7 days (configurable) → `CANCELLED` by the ticker. Parent unlinked → `CANCELLED` (`UNLINKED`). Each cancellation publishes `PayoutCancelled`.

**One open request per teen.** A second request while one is `REQUESTED` → 409. Enforced by a partial unique index (`UNIQUE (teen_id) WHERE state = 'REQUESTED'`) so concurrent requests cannot both succeed, independent of the wallet lock.

---

## UC-09 Parent fulfils a payout through a bank app

**Flow** (Scenario 2, steps 4–7).
1. Parent taps the push; the app opens the payout.
2. Parent picks a bank from the list (Kaspi first, then Freedom, Forte, and others as added) and the app opens that bank's app through a deep link; the parent transfers to the teen with biometrics in the bank app.
3. Parent returns and taps "Transfer complete".
4. Ledger: lock the wallet, `REQUESTED → FULFILLED`, write the debit entry, `PayoutFulfilled`.
5. Teen is notified.

**Trust model.** We do not verify the transfer (no bank API, and we are not a bank). The parent's confirmation is the source of truth and the balance is debited on it. If the money did not arrive, parent and teen sort it out directly. The audit trail records who confirmed and when (SYS-01).

**Support needed.**
- Client: a bank registry in the app, one entry per bank (display name, iOS URL scheme, Android package, fallback). Adding a bank is one registry entry plus the platform declarations (iOS `LSApplicationQueriesSchemes`, Android `<queries>`), which ships with an app release. The backend is bank-agnostic: the ledger, events and payout FSM never mention a bank, so supporting another bank costs no backend change.
- Fallback when a bank app is missing or has no usable deep link: open the app by its store listing or package, and show the amount with a copy button.
- The bank the parent used is not recorded in the MVP. If analytics wants it, add a nullable `payouts.fulfilled_via`.
- Ledger: only a linked parent can confirm; idempotent on `PAYOUT-{payoutID}`; `ledger_entries` append-only, enforced at the DB layer.
- Verify each bank's real deep-link scheme before implementing; the schemes for Kaspi, Freedom and Forte, and whether amount and recipient can be prefilled, are unverified. Only banks with a working, low-effort integration are added; the rest wait.

---

## UC-10 Recurring tasks and deadlines (system-driven)

**Flow.** On each tick the system:
1. Expires `ASSIGNED` tasks whose deadline has passed (`EXPIRED`, `TaskExpired`).
2. Materialises the next occurrence of each active recurring series (`UNIQUE (series_id, occurrence_date)` makes this idempotent).
3. Cancels stale payout requests (UC-08).

**Support needed.**
- The `Ticker` interface and scheduler described in `docs/modules.md`: one scheduled task per ticker with its own ShedLock lock inside the API pods, every minute unless a ticker sets its own interval. Events are not part of the ticker; the Modulith registry delivers them.
- Period boundaries in the series' own timezone (`task_series.timezone`, stored UTC); Kazakhstan has a single zone now, but it is stored per series.
- A missed occurrence expires, and costs the series' penalty if it has one (UC-11). No backfill: a tick creates only the current day's occurrence (in the series' timezone, and only while its due time is still ahead), so days the system missed are never created later.
- Closing a series, or removing the family link, stops further occurrences. Closing is permanent.
- A parent can edit a series (title, description, reward, penalty, due time, timezone) or pause and resume it (`PATCH /task-series/{id}`). Edits and pauses apply to future occurrences only: tasks already created are never touched, and an occurrence that already exists for today keeps its old values. Resuming does not backfill: the next tick creates only the current day's occurrence, and only while its due time is still ahead. A closed series cannot be edited, paused or resumed (409).
- Tick cadence of about one minute is enough; expiry precision beyond that is not needed.

---

## UC-11 A missed task costs money

**Flow.** When creating a task or a recurring series, the parent can set an optional penalty (0 or empty means none). If the teen lets the task expire, the ledger debits the penalty automatically and publishes `PenaltyApplied`; the teen is notified. A free-form manual bonus or penalty is the proposed UC-13.

**Rules.**
- Only a missed deadline counts: `ASSIGNED` past its deadline becomes `EXPIRED`. Submitted, rejected, cancelled (including after an unlink) and approved tasks cost nothing.
- One penalty per task at most (key `PENALTY-TASK-{taskId}`), so a redelivered event does nothing.
- The balance may go negative. A debt blocks payout requests and goal locks until rewards pay it down; releasing a goal still works.
- The teen sees the penalty on the task (`penaltyAmount`) before the deadline. Penalties also appear in the wallet history (`GET /wallet/entries`, kind `PENALTY`, with the task id), visible to the teen and to linked parents.

**Support needed.** The expiry ticker (UC-10), the ledger's `TaskExpired` listener, the compensating `PENALTY` journal entry.

---

## UC-12 Teen sets a savings goal

**Flow.** Teen creates a goal (name, target), locks part of their available balance into it, sees a progress bar (`savedAmount` of `targetAmount`). The money stays in the total balance but is excluded from *available*. The teen can unlock part of it or close the goal, which releases everything saved. A lock cannot take the goal past its target (`exceeds_target`). Goals are visible to the teen only.

**Support needed.** ledger: `savings_goals`; locking funds is a ledger entry (available → locked), not a separate balance. The amount saved in a goal is derived from the entries referencing it. Locked funds are excluded from *available*, so they cannot be requested as a payout until unlocked.

---

## UC-13 User deletes their account

**Flow.** A signed-in parent or teen calls `DELETE /me`. Identity locks the user, refuses while an open payout involves them (`account_has_open_payout`), unlinks every active link like `DELETE /links/{teenId}` (so the other side's tasks are cancelled, series closed, payouts cancelled and the other side notified), anonymises the account (name `Deleted user`, identity key replaced by a tombstone, join code released) and publishes `AccountDeleted`. Notification then removes the user's device tokens. The token stops working at once; signing in again with the same Google account starts a new, empty account.

**Alternates.** Already deleted → 401. Open payout → 409 until the teen cancels it or the parent fulfils or declines it. A remaining balance does not block: it is forfeited and frozen, and the ledger history stays (anonymised) for the former counterpart. Two simultaneous deletes → one 204, the other 401.

**Support needed.** identity: `users.deleted_at`, `User.delete`, `FamilyExit`, `OpenPayoutCheck` (implemented by ledger), `platform.security.DeletedAccounts`. notification: `AccountDeletedListener`.

**Open.** A grace period (undo window) and a hard-purge job for anonymised rows and a deleted teen's proofs are follow-ups, as is the data export a user would want before deleting.
## UC-14 Parent adds a manual bonus or penalty (proposed)

> **Proposed, not a documented requirement.** It comes from the maintainers' review. `UC-05` (task rewards) and `UC-11` (automatic missed-task penalties) are the only money movements the requirements describe.

**Flow.** A linked parent calls `POST /teens/{teenId}/adjustments` with a kind (`BONUS` or `PENALTY`), a positive amount and a reason of 1 to 140 characters. The ledger, in one transaction, locks the teen's wallet, re-checks the link, appends a `MANUAL_BONUS` or `MANUAL_PENALTY` entry that records the parent and the reason, and publishes `BalanceAdjusted`; notification pushes the teen.

**Rules.**
- Parent only, and only for a teen linked to them at that moment.
- A penalty behaves like UC-11: the balance may go negative and a debt blocks payout requests and goal locks.
- Entries are append-only. There is no edit or delete; a mistake is corrected by the opposite adjustment.
- The amount has the same ceiling as a task reward. A required `Idempotency-Key` makes a retry return the first entry.
- The reason is stored on the entry and shown in the wallet history (`GET /wallet/entries`) together with the parent who made it; it also appears in the teen's data export.
- The parent's account can be deleted later without touching the entry: the actor id stays and points to the anonymised user.

**Support needed.** `Wallet.adjust`, the `Adjustment` value object, ledger kinds `MANUAL_BONUS` and `MANUAL_PENALTY` (migration V17), the `BalanceAdjusted` event and its notification copy.

---

## Cross-cutting support (applies to every use case)

| Concern | What it means here |
|---|---|
| Authorization | Every parent→teen action calls `FamilyDirectory.areLinked`. Teens act only on their own resources. Enforce in the service, not just the handler. |
| Idempotency | Every command carries a key; consumers dedupe on the event key (`docs/transactions.md`). Keys come from IDs, never timestamps. |
| Audit | Ledger entries append-only; approvals, payouts, and penalties record actor and timestamp (SYS-01). |
| Notifications | RU/KK copy rendered in notification from the event and the device's locale; no text in domain logic. |
| Privacy | No PII in logs, events, or cache keys; proofs are private objects with metadata stripped. Data (database and object storage) is hosted in Kazakhstan. Every user can download their own data with `GET /me/export` (`docs/api.md`, Privacy). |
| Testing | FSM transitions as unit tests; UC-04, UC-05, UC-08, UC-09, UC-10 as functional tests against Postgres, including concurrent approve, concurrent payout requests, and a tick running twice. |

## Decisions still open

1. Age and consent rules for teen accounts (UC-01).
2. Which Kazakhstan hosting provider offers managed Postgres, S3-compatible storage, and Kubernetes. Whether Google sign-in and FCM (which pass tokens through Google, not stored personal data) satisfy the data law: confirm with counsel.
