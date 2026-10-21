# Conventions & Design Principles

The house style. Idiomatic Go for surface syntax; the design principles below for module shape. When they conflict, Go idioms win on syntax and the design principles win on module shape.

## Design principles

### Deep modules, narrow interfaces

> The best modules are those whose interface is much simpler than their implementation.

- A module's **service facade** (`service.go`) is the narrow interface. Behind it sits the FSM, the transaction orchestration, the locking, the event emission — substantial depth hidden behind a few methods. That is a *deep* module.
- The interface another module sees is narrower still: `media.PresignService` has two methods; `ledger.BalanceReader` has one. Callers see the minimum.
- **Red flag:** a module whose interface has roughly as many methods as its implementation has functions is *shallow* — it adds a layer without hiding complexity. If you are writing a method that just forwards to one store call, question whether the method should exist.

### Information hiding

- Default to unexported. A type, field, or function is exported only if another package genuinely needs it. The `internal/` directory is the coarse boundary; lowercase identifiers are the fine one.
- A module's `domain/`, `store/`, and `http/` packages export nothing to *other modules*. Only `service.go`'s interface(s) and `events.go`'s payload types are the module's public contract.
- Do not leak implementation types across the interface. `BalanceReader.Balance` returns a `ledger.Balance` value object, not a `db.WalletRow`. The persistence shape is hidden.

### Pull complexity downward

> It is more important for a module to have a simple interface than a simple implementation.

- The service absorbs complexity (transaction choreography, idempotency, event emission) so that HTTP handlers and callers stay trivial. A handler should read like: decode → validate → call one service method → encode.
- Don't push a decision onto the caller that the module could make itself. If there is a sensible default, bake it in.

### Define errors out of existence

- Design APIs so error cases don't arise. Example: a duplicate idempotency key is **not an error** — `ApproveTask` treats a replay as success (idempotent), so callers never handle a "already approved" error.
- Recurring tasks are one row per occurrence, created by the task module's ticker, so callers never check "is this recurring?".

### No comments by default

- **Do not write comments.** Code, SQL, and migrations must be self-explanatory through naming and structure. If a comment seems necessary, first try renaming or extracting a well-named function, constant, or type.
- **Only exception:** a business rule that is not intuitive from the code and that a reader could not reasonably infer (e.g. why a missed occurrence carries no penalty, why a payout does not debit until the parent confirms). State the rule in one short line.
- No doc comments on exported identifiers just because Go linters suggest them; configure the linter accordingly. No comments restating what code does, section dividers, commented-out code, or TODOs without a linked issue.
- Locking and transaction choices are expressed in names (`GetWalletForUpdate`, `UpdateTaskState` with a version check), not comments.
- Code samples in `docs/` may be annotated to teach; that does not carry into the codebase.

### General-purpose over special-purpose

- Prefer a slightly more general interface that serves several callers over a narrow one per caller — *when* it doesn't complicate the common case. Don't over-generalize speculatively (see "no speculative design" below); generalize when the second caller appears.

## Domain-driven design (applied pragmatically)

DDD gives us a shared language and the consistency boundaries; it is not a reason to add layers. The vocabulary and the aggregate map are in `docs/modules.md`; these are the coding rules.

- **Ubiquitous language.** Names in `domain/`, SQL, API paths and events come from the glossary in `docs/modules.md`. If the code and the business use different words for one thing, rename the code. Prefer the business's verb (`Approve`, `RequestPayout`, `FulfilPayout`) over generic ones (`Update`, `Process`, `SetState`).
- **Aggregates are in `domain/`, pure.** An aggregate root enforces its own invariants through methods that express a business operation (`task.Approve(parentID, now)`), not setters. State changes only through those methods, so an invalid state cannot be constructed. No exported mutable fields where an invariant exists.
- **Construct valid, stay valid.** Constructors (`NewTask`, `NewMoney`) validate and return an error; there is no half-built object. Rehydrating from the database goes through a separate `Rehydrate` that trusts stored data.
- **Value objects** for anything defined by its value: `Money` (amount in minor units plus currency, with arithmetic that refuses to mix currencies), `JoinCode`, `IdempotencyKey`, state types. They are immutable, compared by value, and validated at creation.
- **One aggregate per transaction.** A service loads one aggregate through its repository, calls a domain method, saves it, and writes the outbox events in the same transaction. Other aggregates are referenced by ID only, and never navigated. Documented exceptions (`docs/modules.md`) stay inside one context.
- **Domain events come from the aggregate.** A domain method returns the events that happened (`TaskApprovedEvent`); the service writes them to the outbox. Events are named in the past tense, are facts, carry IDs and small values, and are part of the module's public contract in `events.go`.
- **Repositories are the `store/` package, one per aggregate root.** They load and save whole aggregates and hide SQL; no query returns a partial aggregate for the domain to modify. Reads for screens (dashboards, lists) may be separate query methods that return plain read models, since they change nothing.
- **Domain services** only for logic that spans several aggregates of one context and belongs to none of them. If it fits an aggregate, put it there.
- **Anti-corruption layer.** External systems (Google, FCM, S3, bank apps) are wrapped behind an interface owned by the module that needs them, translating to and from domain types. Their DTOs never reach `domain/`.
- **Be pragmatic.** Use the tactical patterns where there are rules to protect: task, wallet, payout, join code. Plain data handling is fine where there are none, such as device tokens or the delivered log. Do not add a layer, interface or pattern whose only purpose is to look like DDD.

## Go idioms (non-negotiable)

- **Formatting:** `gofmt` / `goimports`. CI fails on unformatted code. No debate about style.
- **Errors:** return `error` as the last value; wrap with `fmt.Errorf("doing X: %w", err)` to preserve the chain; check with `errors.Is` / `errors.As`. Sentinel errors (`var ErrInvalidTransition = errors.New(...)`) for conditions callers branch on. Never `panic` in request paths — panic is for programmer errors (nil that can't be nil), recovered only by top-level middleware.
- **Context:** `context.Context` is the first parameter of every function that does I/O or can block. Never store a context in a struct. Propagate it everywhere — DB calls, HTTP clients, the bus.
- **No naked `interface{}`/`any`** in domain or service signatures. Events serialize through `any` only at the outbox JSON boundary.
- **Constructor injection, no globals.** No package-level mutable state, no singletons, no service locator. Dependencies arrive via `New(...)` constructors; `cmd/api/main.go` is the composition root that wires everything.
- **Interfaces are defined by the consumer, not the producer.** The service that *needs* a store declares the narrow store interface it uses; the concrete `*Store` satisfies it implicitly. Keep interfaces small (1–3 methods is ideal).
- **Accept interfaces, return structs.** Constructors take interface dependencies and return concrete types.
- **Concurrency:** share memory by communicating where it fits; otherwise guard with a mutex and keep the critical section tiny. Every goroutine has a clear owner and a shutdown path (context cancellation). `go test -race` is mandatory.

## Repository layout

```
tiyn/
  cmd/
    api/main.go              # composition root: wire modules, start HTTP + relay
    tiyn/main.go             # CLI: `tick` (CronJob one-shot), `migrate`
  internal/
    identity/  task/  ledger/  media/  notification/
                             # each: service.go, <module>.go (iface), events.go,
                             #       domain/, store/, http/
    platform/                # cross-cutting, owned by no bounded context
      db/                    # pgxpool setup, execTx helper, isUniqueViolation
      outbox/                # relay, outbox helpers
      eventbus/              # in-process dispatcher: handlers, retries, dead letters
      httpx/                 # chi middleware: auth, request-id, recover, logging
      config/                # env loading
      migrations/            # platform schema: shared currency type, updated_at trigger, outbox
                             # every module keeps its goose migrations in internal/<module>/store/migrations;
                             # each has its own goose version table, platform is applied first
  db/
    queries/                 # sqlc source .sql, per module
  test/
    functional/              # cross-module PRD-scenario acceptance tests
  sqlc.yaml
  go.mod
```

## Naming

- Packages: short, lowercase, no underscores, named for what they provide (`ledger`, not `ledgerservice`). Avoid stutter: `ledger.Service`, not `ledger.LedgerService`.
- Files: `service.go`, `service_test.go`; one FSM per file in `domain/` (`task.go`, `payout.go`).
- Events: `<Noun><PastTenseVerb>Event` — `TaskApprovedEvent`, `PayoutRequestedEvent`. Past tense: events are facts that already happened.
- Commands (service method inputs): `<Verb><Noun>Cmd` — `ApproveTaskCmd`.
- Money: `BIGINT` in **minor units** (tiyn for KZT; 20,000 KZT is stored as 2,000,000), always paired with a `currency`. Never floats. Fields are named `Amount`/`...Amount` with a separate `Currency`; conversion to display units happens only at the API edge.

## Scope discipline

- **No speculative design.** Build what the PRD's MVP requires. Do not add configurability, abstraction layers, or "future-proofing" for needs that don't exist yet. The module boundaries already make future change cheap; that is the only future-proofing we pay for up front.
- **No refactoring of unrelated code** in a feature change. If you spot a problem outside your task, note it, don't fix it silently.
- **New files/docs:** don't create READMEs, summaries, or extra docs unless asked. The `docs/` set is the canonical documentation; extend it, don't fork it.

## Security & privacy (baseline, always)

- No PII in logs, error messages, event payloads, or cache keys beyond what a consumer strictly needs. Profiles are private; no feature exposes a minor's data to another user.
- Secrets come from env/Vault, never code or config files in the repo.
- Every mutating endpoint authorizes the caller against the family link (`identity.FamilyDirectory`) — a parent may only act on their own linked teen. This check is not optional and not inferable from the JWT alone.
- Input validation at the HTTP edge (`go-playground/validator`); domain invariants re-checked in the FSM (never trust the edge for a money-affecting invariant).

## Tooling

```bash
gofmt -l .            # must be empty
go vet ./...          # must be clean
go build ./...        # must compile
go test -race ./...   # must pass (unit always; functional needs Docker)
sqlc generate         # after editing a module's store/queries/*.sql or migrations; the generated store/gen code is committed
goose ... up          # apply migrations
```
Wire these into a `Makefile` and the GitLab CI pipeline (GitLab is the house CI). CI is the enforcement point for everything in this document.
