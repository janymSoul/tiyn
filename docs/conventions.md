# Conventions & Design Principles

The house style. Idiomatic Java for surface syntax; the design principles below for module shape. When they conflict, Java idioms win on syntax and the design principles win on module shape.

## Design principles

### Deep modules, narrow interfaces

> The best modules are those whose interface is much simpler than their implementation.

- A feature's **service** is the narrow interface for its own controller. Behind it sit the FSM, the transaction orchestration, the locking, the event emission — substantial depth hidden behind a few methods. That is a *deep* module.
- The interface another module sees is narrower still and lives in the module's root package: `media.Storage` has two methods; `ledger.BalanceReader` has one. Callers see the minimum.
- **Red flag:** a module whose interface has roughly as many methods as its implementation has classes is *shallow* — it adds a layer without hiding complexity. If you are writing a method that just forwards to one store call, question whether the method should exist.

### Information hiding

- Default to package-private. A type, field, or method is `public` only if another package genuinely needs it. The module's root package is the only one other modules may use; Modulith enforces that, and package-private visibility is the fine-grained boundary inside a module.
- Only a module's root package is visible to other modules: its interfaces and event records are the public contract, and every feature sub-package is internal. Inside a module, a class is `public` only when another feature package needs it; services, stores, controllers and request/response records are package-private.
- Do not leak implementation types across the interface. `BalanceReader.balance` returns a `Balance` value object, not a jOOQ `WalletsRecord`. The persistence shape is hidden.

### Pull complexity downward

> It is more important for a module to have a simple interface than a simple implementation.

- The service absorbs complexity (transaction choreography, idempotency, event emission, retries) so that controllers and callers stay trivial. A controller method should read like: take the principal and request → call one service method → map to a response record.
- Don't push a decision onto the caller that the module could make itself. If there is a sensible default, bake it in.

### Define errors out of existence

- Design APIs so error cases don't arise. Example: a duplicate idempotency key is **not an error** — `approveTask` treats a replay as success (idempotent), so callers never handle an "already approved" error. Joining a link twice is a no-op, and unlinking twice is 204.
- Recurring tasks are one row per occurrence, created by the task module's ticker, so callers never check "is this recurring?".

### No comments by default

- **Do not write comments.** Code, SQL, and migrations must be self-explanatory through naming and structure. If a comment seems necessary, first try renaming or extracting a well-named method, constant, or type.
- **Only exception:** a business rule that is not intuitive from the code and that a reader could not reasonably infer (e.g. why a missed occurrence carries no penalty, why a payout does not debit until the parent confirms). State the rule in one short line.
- No Javadoc on public types just because a linter suggests it; configure the linter accordingly. No comments restating what code does, section dividers, commented-out code, or TODOs without a linked issue.
- Locking and transaction choices are expressed in names (`findForUpdate`, a version-checked `save`), not comments.
- Code samples in `docs/` may be annotated to teach; that does not carry into the codebase.

### General-purpose over special-purpose

- Prefer a slightly more general interface that serves several callers over a narrow one per caller — *when* it doesn't complicate the common case. Don't over-generalize speculatively (see "Scope discipline" below); generalize when the second caller appears.

## Domain-driven design (applied pragmatically)

DDD gives us a shared language and the consistency boundaries; it is not a reason to add layers. The vocabulary and the aggregate map are in `docs/modules.md`; these are the coding rules.

- **Ubiquitous language.** Names in aggregates, SQL, API paths and events come from the glossary in `docs/modules.md`. If the code and the business use different words for one thing, rename the code. Prefer the business's verb (`approve`, `requestPayout`, `fulfilPayout`) over generic ones (`update`, `process`, `setState`).
- **Aggregates are pure.** They import no jOOQ, Spring or web types. An aggregate root enforces its own invariants through methods that express a business operation (`task.approve(parentId, now)`), not setters. State changes only through those methods, so an invalid state cannot be constructed. No public mutable fields where an invariant exists. `FamilyLink` is the reference: private fields, `between`, `restore`, `link`, `unlink`, `pullEvents`.
- **Construct valid, stay valid.** Static factories (`User.register`, `FamilyLink.create`) validate and throw a domain exception; there is no half-built object. Rehydrating from the database goes through a separate `restore`/constructor that trusts stored data.
- **Value objects** are records, immutable and validated at creation: `Money` (amount in minor units plus currency, with arithmetic that refuses to mix currencies), `JoinCode`, state enums. Compare by value.
- **One aggregate per transaction.** A service loads one aggregate through its store, calls a domain method, saves it, and publishes the events in the same transaction. Other aggregates are referenced by ID only, and never navigated. Documented exceptions (`docs/modules.md`) stay inside one context.
- **Domain events come from the aggregate.** A domain method decides what happened and records the event (`FamilyLink.link()` records `FamilyLinked`); the service calls `pullEvents()` and publishes them in the same transaction. The service never builds an event or reads aggregate state to decide what to publish. Events are records, named in the past tense without an `Event` suffix (`TaskApproved`, `FamilyLinked`), are facts, carry IDs and small values, and sit in the module's root package as part of its public contract.
- **Tell, don't ask.** The service calls one intention-revealing method (`link.link()`, `link.unlink(now)`) and lets the aggregate decide between first link, relink and no-op. A service that branches on aggregate state, or on whether a row exists, is doing the domain's job. Existence is the one thing a service may branch on, because only the repository knows it; hide even that behind a factory (`FamilyLink.between(parent, teen)` for a pair that was never linked).
- **Invariants that span two aggregates go in a factory** that takes both (`FamilyLink.between(User parent, User teen)` enforces parent/teen roles) and then keeps only IDs. Authorization (who may call) stays in the service.
- **Stores are repositories, one per aggregate root.** They load and save whole aggregates and hide SQL; no query returns a partial aggregate for the domain to modify. Reads for screens (dashboards, lists) may be separate query methods that return plain read models, since they change nothing.
- **Domain services** only for logic that spans several aggregates of one context and belongs to none of them. If it fits an aggregate, put it there.
- **Anti-corruption layer.** External systems (Google, FCM, S3, bank apps) are wrapped behind an interface in their own package, with its own exceptions, and the calling feature translates them to domain errors. Their DTOs never reach an aggregate. `identity.google.GoogleVerifier` is the reference: `account` maps `InvalidGoogleTokenException` and `GoogleUnavailableException` to `IdentityError`s, so `google` never depends on `account`.
- **Be pragmatic.** Use the tactical patterns where there are rules to protect: task, wallet, payout, join code. Plain data handling is fine where there are none, such as device tokens or the delivered log. Do not add a layer, interface or pattern whose only purpose is to look like DDD.

## Java idioms (non-negotiable)

- **Formatting:** Spotless with Palantir Java Format. `./mvnw spotless:apply` before committing; `spotless:check` runs in `verify`, so CI fails on unformatted code. No debate about style.
- **Errors:** domain failures are unchecked exceptions that carry a stable code (`IdentityException(IdentityError)`), mapped to HTTP in the module's `@RestControllerAdvice`. Never catch `Exception` to swallow; log with the cause or rethrow. Do not use exceptions for expected branching you can express as a return value (`Optional`, a boolean "changed").
- **Immutability:** records for DTOs, events and value objects. `final` fields; no setters on aggregates. Collections returned from the domain are unmodifiable.
- **Null:** no `null` in public signatures except where a nullable column genuinely means "absent" (`User.joinCode` for teens); prefer `Optional` on return types of lookups. Never return `null` collections.
- **Constructor injection, no field injection, no `@Autowired` on fields.** One constructor, `final` fields. Beans are package-private unless another package needs them.
- **Time and randomness are injected:** use the `Clock` bean and a `SecureRandom` bean, never `Instant.now()` or `new Random()` in logic you want to test.
- **Interfaces:** define one when there are two implementations, a real external boundary (Google, S3, FCM), or a cross-module contract. Not for every class.
- **Concurrency:** rely on the database for correctness (locks, unique constraints, version checks), not on `synchronized` or in-memory state. A bean is a singleton, so it must hold no per-request mutable state.
- **Money:** `long` in minor units, never `double`/`float`/`BigDecimal` in the domain.

## Repository layout

```
tiyn/
  pom.xml                       # Spring Boot parent, Modulith BOM, jOOQ codegen, Spotless
  mvnw, .mvn/                   # always use ./mvnw
  docker-compose.yml            # local Postgres + SeaweedFS
  .env.example                  # every environment variable the app reads
  src/main/java/kz/tiyn/
    TiynApplication.java
    platform/                   # shared kernel, open module; owns no business data
      security/                 #   JWT verification and issuing, Principal, Role
      web/                      #   ApiException, ErrorBody, GlobalExceptionHandler
      tx/                       #   ConflictException, ConflictRetry (whole-transaction retry)
      tick/                     #   Ticker, TickScheduler, ShedLock configuration
    identity/ task/ ledger/ media/ notification/
                                # each: root-package API + events, plus one sub-package per feature
  src/main/resources/
    application.yml
    db/migration/               # V1__platform … Vn; one Postgres schema per module
  src/test/java/kz/tiyn/
    ModularityTests.java        # Modulith verify + table-ownership rule
    support/                    # @IntegrationTest, Postgres and fake-Google configs, TestTokens
    <module>/…                  # tests mirror the main packages
  docs/                         # the canonical documentation
```

Generated jOOQ code lives in `target/` and is not committed.

## Naming

- Packages: short, lowercase, named for what they provide. Module packages are the bounded-context names (`ledger`, not `ledgerservice`). Sub-packages are features named for the business capability (`account`, `family`, `payout`), plus an ACL package named for the external system (`google`). Do not name sub-packages after layers.
- Classes: `<Feature>Service`, `<Feature>Controller`, `<Aggregate>Store` (`UserStore`, `LinkStore`), `<Feature>Directory` for a public cross-feature interface, `…Request`/`…Response` records beside the controller. Avoid stutter: `ledger.payout.PayoutService` is fine, `ledger.payout.PayoutPayoutService` is not.
- Tests: `<Class>Tests` (plural), methods that read as sentences (`onlyTeensCanJoinAndOnlyParentsCanUnlink`).
- Events: `<Noun><PastTenseVerb>` — `TaskApproved`, `PayoutRequested`. Past tense: events are facts that already happened.
- Commands (service method inputs): `<Verb><Noun>` records — `ApproveTask`.
- Migrations: `V<n>__<module>.sql`, never edited after a release; add a new version instead. (Before the first deployment the existing files may be edited, as was done for `idp_sub`.)
- Money: `BIGINT` in **minor units** (tiyn for KZT; 20,000 KZT is stored as 2,000,000), always paired with a `currency`. Never floats. Fields are named `amount`/`...Amount` with a separate `currency`; conversion to display units happens only at the API edge.

## Scope discipline

- **No speculative design.** Build what the PRD's MVP requires. Do not add configurability, abstraction layers, or "future-proofing" for needs that don't exist yet. The module boundaries already make future change cheap; that is the only future-proofing we pay for up front.
- **No refactoring of unrelated code** in a feature change. If you spot a problem outside your task, note it, don't fix it silently.
- **New files/docs:** don't create READMEs, summaries, or extra docs unless asked. The `docs/` set is the canonical documentation; extend it, don't fork it.

## Security & privacy (baseline, always)

- No PII in logs, error messages, event payloads, or cache keys beyond what a consumer strictly needs. Profiles are private; no feature exposes a minor's data to another user. Log identifiers, never names or tokens.
- Secrets come from env/Vault, never code or config files in the repo. `application.yml` has no defaults for secrets; startup fails when `JWT_SECRET` (at least 32 bytes) or `GOOGLE_CLIENT_IDS` is missing or invalid. `.env.example` uses placeholders that the validation rejects.
- Every mutating endpoint authorizes the caller against the family link (`FamilyDirectory.areLinked`) — a parent may only act on their own linked teen. This check is not optional and not inferable from the JWT alone. The role in the token routes the request; the database decides.
- Input validation at the HTTP edge (Bean Validation, record request bodies, `FAIL_ON_UNKNOWN_PROPERTIES` on); domain invariants re-checked in the aggregate (never trust the edge for a money-affecting invariant).
- Error bodies are `{"error": "<code>"}` with a stable snake_case code. Unknown and malformed inputs that could reveal existence (a join code) share one code.

## Tooling

```bash
./mvnw spotless:apply        # format; run before committing
./mvnw verify                # compile, jOOQ codegen, tests, spotless:check (needs Docker)
./mvnw -q -Dtest=ModularityTests test    # module boundaries only
```
Always the wrapper, never a system `mvn`. Wire `./mvnw verify` into the GitLab CI pipeline (GitLab is the house CI) with a Docker-capable runner. CI is the enforcement point for everything in this document. Checkstyle, SpotBugs and JaCoCo are not configured yet; add them when the first module beyond identity lands.
