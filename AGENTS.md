# tiyn — Agent Operating Guide

**Read this first, every time.** `tiyn` is a teen financial-literacy + task app for the Kazakhstan market (parent funds + approves; teen earns via chores and academic milestones; virtual ledger + bank-app deep-link cash-out, Kaspi first). This file is the entry point for any coding agent working in this repo. It tells you *how* to work here; the `docs/` set tells you *what* the system is.

## Prime directives

1. **Respect module boundaries.** Code lives under `internal/<module>/`. A module's internals are invisible to other modules by Go's compiler (the `internal/` rule). You may only cross a boundary two ways: a **narrow Go interface** (sync) or a **published domain event** (async). Never import another module's `domain/`, `store/`, or `http/`. Never read or write another module's tables. See `docs/modules.md`.

2. **Transactions are explicit and owned by the service layer.** A service method opens exactly one transaction via `execTx`; domain code and repositories never open their own. A state transition and all its side effects (ledger write, outbox event) commit or roll back as one unit. See `docs/transactions.md`.

3. **Domain models are finite state machines, and they are pure.** Entities validate their own transitions with no dependency on the database, HTTP, or the event bus. Persistence is explicit and lives outside the domain. See `docs/modules.md` and `docs/conventions.md`.

4. **Every cross-module effect goes through the outbox.** Events are written to `outbox_events` *in the same transaction* as the business change, then a relay publishes them at-least-once. Consumers must be idempotent. See `docs/events-outbox.md`.

5. **Code is tested before it is called done.** Pure domain logic → unit tests (fast, no DB). Service + HTTP + outbox → functional tests against a real Postgres (Testcontainers-go). A change without tests is not complete. See `docs/testing.md`.

6. **No comments.** Code and SQL must be self-explanatory; rename or extract instead of commenting. The only exception is a short note for a non-intuitive business rule. See `docs/conventions.md`.
7. **Design deep modules with narrow interfaces**. A module should expose a small interface that hides a lot of implementation. Push complexity down, not onto callers. Avoid shallow pass-through layers. See `docs/conventions.md`.

## Definition of done

Before you say a task is finished, all of the following must hold:

- [ ] Code compiles: `go build ./...`
- [ ] Formatted and vetted: `gofmt -l .` clean, `go vet ./...` clean
- [ ] Tests pass with the race detector: `go test -race ./...` (functional tests start Postgres through Testcontainers and need Docker; `TIYN_SKIP_FUNCTIONAL=1` skips them)
- [ ] No module imports another module's internals (verify by inspection; the compiler blocks `internal/` leaks but not sibling-package reaches)
- [ ] No new cross-context foreign keys in migrations
- [ ] New cross-module effects go through an event, not a direct DB write

## Where to look

| Question | Doc |
|---|---|
| What is the system, what are the layers, what's the stack? | `docs/architecture.md` |
| What are the modules, their boundaries, their contracts? | `docs/modules.md` |
| How do I handle transactions, locking, idempotency? | `docs/transactions.md` |
| How do events and the outbox relay work? | `docs/events-outbox.md` |
| How do I test this? | `docs/testing.md` |
| Go style, design principles, error handling, naming? | `docs/conventions.md` |

## Ask before you assume

If a requirement is ambiguous, a boundary is unclear, or a change seems to need a cross-module DB read, **stop and ask** rather than reaching across a boundary. The architecture's value is in the boundaries; eroding them silently is the one unrecoverable mistake.
