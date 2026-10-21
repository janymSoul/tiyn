# Conventions & Design Principles

The house style. Grounded in Ousterhout's *A Philosophy of Software Design* (APOSD) and idiomatic Go. When the two conflict, idiomatic Go wins on surface syntax; APOSD wins on module shape.

## APOSD, applied to this codebase

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
- Recurring-task re-arming is handled inside the FSM, not by making the caller check "is this recurring?" and re-create it.

### Comments explain *why* and *what's non-obvious*

- Minimal comments on *what* the code does (the code says that). Comment the *why*: why pessimistic here and optimistic there, why this event carries `locale`, why rollback-after-commit is ignored.
- Every exported type and function has a doc comment (Go convention), but it describes contract and intent, not mechanics.
- A query's SQL comment states its locking choice: `-- locks the wallet row; see docs/transactions.md`.

### General-purpose over special-purpose

- Prefer a slightly more general interface that serves several callers over a narrow one per caller — *when* it doesn't complicate the common case. Don't over-generalize speculatively (see "no speculative design" below); generalize when the second caller appears.

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
  internal/
    identity/  task/  ledger/  media/  notification/
                             # each: service.go, <module>.go (iface), events.go,
                             #       domain/, store/, http/
    platform/                # cross-cutting, owned by no bounded context
      db/                    # pgxpool setup, execTx helper, isUniqueViolation
      outbox/                # relay, outbox helpers
      eventbus/              # Watermill setup, topic naming, DLQ middleware
      httpx/                 # chi middleware: auth, request-id, recover, logging
      config/                # env loading
  db/
    migrations/              # goose; subdirs or prefixes per module, no cross-context FKs
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
- Money: integer **tenge** (or minor units if we ever need sub-tenge — decide once and never mix). Never floats for money. Name fields `...KZT` to make the unit explicit.

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
sqlc generate         # after editing db/queries/*.sql
goose ... up          # apply migrations
```
Wire these into a `Makefile` and the GitLab CI pipeline (GitLab is the house CI). CI is the enforcement point for everything in this document.
