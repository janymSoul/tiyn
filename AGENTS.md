# tiyn — Agent Operating Guide

**Read this first, every time.** `tiyn` is a teen financial-literacy + task app for the Kazakhstan market (parent funds + approves; teen earns via chores and academic milestones; virtual ledger + bank-app deep-link cash-out, Kaspi first). This file is the entry point for any coding agent working in this repo. It tells you *how* to work here; the `docs/` set tells you *what* the system is.

The backend is Java 21, Spring Boot 3.5 and Spring Modulith, with jOOQ over PostgreSQL and Flyway migrations. It was rewritten from Go; the Go code is only in git history (`git log 38c7e34^`).

## Prime directives

1. **Respect module boundaries.** Code lives under `kz.tiyn.<module>`. Only a module's **root package** is visible to other modules: its interfaces and event records. Never use another module's feature sub-packages (`account`, `family`, …). Inside a module, sub-packages are features and their classes are package-private; do not split by layer. Never read or write another module's tables. `ModularityTests` fails the build on both. See `docs/modules.md`.

2. **Transactions are explicit and owned by the service layer.** A service method opens exactly one transaction (`@Transactional`, or a `TransactionTemplate` when it retries). Domain code and stores never open their own. A state transition and its published events commit or roll back as one unit. See `docs/transactions.md`.

3. **Domain models are finite state machines, and they are pure.** Aggregates validate their own transitions with no dependency on the database, Spring web or the event publisher. Persistence is explicit and lives outside the domain. See `docs/modules.md` and `docs/conventions.md`.

4. **Every cross-module effect is a published event.** Aggregates record events and the store's `save` publishes them (through `ApplicationEventPublisher`) inside the service's transaction, so never publish from a service by hand; Spring Modulith persists a publication per listener and delivers it at-least-once. Listeners must be idempotent and state-based. See `docs/events-outbox.md`.

5. **Code is tested before it is called done.** Pure domain logic → unit tests (fast, no Spring). Service + HTTP + events → integration tests against a real Postgres (Testcontainers, `@IntegrationTest`). A change without tests is not complete. See `docs/testing.md`.

6. **No comments.** Code and SQL must be self-explanatory; rename or extract instead of commenting. The only exception is a short note for a non-intuitive business rule. See `docs/conventions.md`.

7. **Model the domain with DDD, pragmatically.** A module is a bounded context with its own language. Business rules live in the aggregates (pure classes inside their feature package); an aggregate is the consistency boundary and normally one transaction changes one aggregate. Name things in the ubiquitous language (see the glossary in `docs/modules.md`). Use the tactical patterns where there are real invariants (task, wallet, payout) and plain code where there are none.

8. **Design deep modules with narrow interfaces.** A module should expose a small interface that hides a lot of implementation. Push complexity down, not onto callers. Avoid shallow pass-through layers. See `docs/conventions.md`.

## Definition of done

Before you say a task is finished, all of the following must hold:

- [ ] `./mvnw spotless:apply && ./mvnw verify` is green (compiles, generates jOOQ, passes all tests including `ModularityTests`, `spotless:check` clean). Docker must be running: the build starts Postgres for code generation.
- [ ] No module uses another module's internals or tables (`ModularityTests` covers both; new modules are added to `TABLE_OWNERS`)
- [ ] No new cross-context foreign keys in migrations
- [ ] New cross-module effects go through an event, not a direct DB write
- [ ] New schema changes are new Flyway versions (`V<n>__…`); a released migration is never edited
- [ ] Always `./mvnw`, never a system `mvn`

## Where to look

| Question | Doc |
|---|---|
| What is the system, what are the layers, what's the stack? | `docs/architecture.md` |
| What are the modules, their boundaries, their contracts? | `docs/modules.md` |
| How do I handle transactions, locking, idempotency? | `docs/transactions.md` |
| How do events and the publication registry work? | `docs/events-outbox.md` |
| How do I test this? | `docs/testing.md` |
| Java style, design principles, error handling, naming? | `docs/conventions.md` |
| What must the product do? | `docs/use-cases.md` |

## Environment

Every variable the app reads is in `.env.example`. `JWT_SECRET` (at least 32 bytes) and `GOOGLE_CLIENT_IDS` have no defaults and the app refuses to start without them. `docker-compose.yml` runs local Postgres 16 and SeaweedFS.

## Ask before you assume

If a requirement is ambiguous, a boundary is unclear, or a change seems to need a cross-module DB read, **stop and ask** rather than reaching across a boundary. The architecture's value is in the boundaries; eroding them silently is the one unrecoverable mistake.
