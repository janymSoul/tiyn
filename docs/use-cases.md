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
| UC-11 | Parent applies a penalty | PAR-04 | Medium |
| UC-12 | Teen sets a savings goal | TEN-04 | Medium |

XP/levels (GAM-01) are out of scope here; they would be a sixth module reacting to `TaskApproved`.

---

## UC-01 Sign in and link a teen to a family

**Flow.**
1. Parent signs in with Google, choosing the role `PARENT` at first sign-in; identity verifies the Google ID token and issues the app JWT (first sign-in creates the account and its permanent join code). An account has exactly one role.
2. Parent shares their join code with the teen (shown in the app; sent in a messenger, SMS, or told in person).
3. Teen signs in with Google (first sign-in creates the teen account) and enters the code.
4. Identity finds the parent by code and creates the `family_links` row.
5. `FamilyLinked(parentId, teenId, generation)` is published. The API for these steps is in `docs/modules.md` ("HTTP API").

**Alternates.** Unknown code → generic "invalid code". Repeated failures → rate limit per device/IP. Teen already linked to this parent → no-op. A teen entering a second parent's code → links to that parent too. Parent removes a link later → teen's tasks are cancelled, wallet history is kept.

**Support needed.**
- identity: Google ID token verification, `users.join_code` (permanent, unique, no look-alike characters; attempts are rate-limited at the ingress), `FamilyDirectory.areLinked`.
- ledger consumes the event → creates the teen's wallet (idempotent: the wallet key is `(teen_id, currency)`). notification consumes it → welcome push.

**Open.**
- **Age and consent.** Teens are 11–17. Confirm whether under-14s can hold a Google account in Kazakhstan, and what parental consent the data-protection law requires. Likely needs an ADR.
- **Apple.** Not in MVP: Google only. The provider-agnostic identity design allows adding Sign in with Apple later. Revisit if iOS App Store review requires it.
- **Parent confirmation of a join.** Deferred. The join code is permanent and reusable, so anyone who learns it can link to that parent. The accepted risk is low for a family app; if it grows, add a pending-request step (identity-owned `join_requests`).

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

**Support needed.** The canonical flow in `docs/transactions.md`. The task transaction writes task tables and publishes the event only; the credit happens in the ledger's own transaction.

**Notes.**
- Eventual consistency: between approval and the ledger listener running (normally milliseconds, longer after a failure) the parent sees "approved" before the teen's balance moves. UI should not promise "immediately credited".
- A redo returns the task to `ASSIGNED`; if the deadline passes meanwhile, it expires like any other assigned task.

---

## UC-06 Parent cancels a task

**Flow.** Parent cancels a task in `ASSIGNED` or `SUBMITTED` (e.g. created by mistake, or the plan changed). FSM → `CANCELLED`, `TaskCancelled` → teen is notified. For a recurring series, the parent chooses "this occurrence" or "this and all future" (the series is closed, so no further occurrences are created). Cancelling a milestone also cancels its open tasks, each notifying the teen.

**Alternates.** Already `APPROVED`/`REJECTED`/`EXPIRED` → an invalid-transition error (409). Cancelling an approved task does not claw back money; use a penalty (UC-11) for that.

---

## UC-07 Teen views balance and tasks

**Flow.** Dashboard shows *Approved* and *Available* balance, *Pending* funds (TEN-01), and tasks grouped into "Daily Chores" and "Major Milestones" (TEN-02). A milestone shows progress computed from its approved tasks ("3 of 5").

**Support needed.**
- ledger: `BalanceReader.balance` → approved, locked, available.
- task: `PendingReader.pendingReward` → sum of rewards of `SUBMITTED` tasks; plus a list endpoint filtered by teen and state.
- The dashboard HTTP handler composes both. Ledger never reads task tables.

---

## UC-08 Teen requests a payout

**Flow** (Scenario 2, steps 1–3).
1. Teen enters an amount ≤ available balance.
2. Ledger, in one transaction: lock the wallet `FOR UPDATE`, compute available (approved − open requests − locked), check amount ≤ available, create the `payouts` row `REQUESTED`, write `PayoutRequested`.
3. Parent receives a high-priority push.

Nothing is debited yet; the open request only lowers *available*, so several requests cannot exceed the balance.

**Alternates.** Amount > available, ≤ 0, or below a minimum → 422. Double-submit → idempotency key returns the original payout. Teen cancels their own request → `CANCELLED`. Parent declines → `CANCELLED` with a reason. Unanswered for N days → `CANCELLED` by the ticker. Each cancellation publishes `PayoutCancelled`.

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
- The `Ticker` interface and scheduler described in `docs/modules.md`: `@Scheduled` with a ShedLock inside the API pods, every minute. Events are not part of the ticker; the Modulith registry delivers them.
- Period boundaries in the series' own timezone (`task_series.timezone`, stored UTC); Kazakhstan has a single zone now, but it is stored per series.
- A missed occurrence expires; no automatic penalty. No backfill: a tick creates only the current day's occurrence (in the series' timezone, and only while its due time is still ahead), so days the system missed are never created later.
- Closing a series, or removing the family link, stops further occurrences.
- Tick cadence of about one minute is enough; expiry precision beyond that is not needed.

---

## UC-11 Parent applies a penalty

**Flow.** Parent enters an amount and an optional note; ledger debits the wallet under `FOR UPDATE` and writes `PenaltyApplied`; the teen is notified.

**Support needed.** Compensating debit entry with the note kept for the audit trail; authorization via `FamilyDirectory`.

**Open.** Penalty above available funds: recommend reject with a clear message (the service checks available funds under the wallet lock; the `CHECK` constraints are only the backstop). Penalties apply to available funds only, not to funds in open payout requests or goals.

---

## UC-12 Teen sets a savings goal

**Flow.** Teen creates a goal (name, target), moves part of their available balance into it, sees a progress bar.

**Support needed.** ledger: `savings_goals`; locking funds is a ledger entry (available → locked), not a separate balance. The amount saved in a goal is derived from the entries referencing it. Locked funds are excluded from *available*, so they cannot be requested as a payout until unlocked.

---

## Cross-cutting support (applies to every use case)

| Concern | What it means here |
|---|---|
| Authorization | Every parent→teen action calls `FamilyDirectory.areLinked`. Teens act only on their own resources. Enforce in the service, not just the handler. |
| Idempotency | Every command carries a key; consumers dedupe on the event key (`docs/transactions.md`). Keys come from IDs, never timestamps. |
| Audit | Ledger entries append-only; approvals, payouts, and penalties record actor and timestamp (SYS-01). |
| Notifications | RU/KK copy rendered in notification from the event and the device's locale; no text in domain logic. |
| Privacy | No PII in logs, events, or cache keys; proofs are private objects with metadata stripped. Data (database and object storage) is hosted in Kazakhstan. |
| Testing | FSM transitions as unit tests; UC-04, UC-05, UC-08, UC-09, UC-10 as functional tests against Postgres, including concurrent approve, concurrent payout requests, and a tick running twice. |

## Decisions still open

1. Age and consent rules for teen accounts (UC-01).
2. Which Kazakhstan hosting provider offers managed Postgres, S3-compatible storage, and Kubernetes. Whether Google sign-in and FCM (which pass tokens through Google, not stored personal data) satisfy the data law: confirm with counsel.
