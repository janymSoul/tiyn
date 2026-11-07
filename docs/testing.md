# Testing Strategy

Testability is an architectural property here, not an afterthought. The module shape (pure domain, explicit persistence, narrow interfaces) exists partly so that each layer is testable in the cheapest way that still gives a real guarantee. A change is not done until it is tested at the right level.

## The test pyramid, mapped to our layers

| Layer | Test type | Speed | Dependencies | What it proves |
|---|---|---|---|---|
| Aggregates and value objects (FSM, invariants) | **Unit** (plain JUnit) | microseconds | none | Transition guards, invariants, calculations are correct |
| Service (orchestration) | **Unit** with fakes, or **integration** | milliseconds–seconds | fake stores, or real Postgres | The command sequences the right steps, handles errors, emits the right event |
| Store + service + controller | **Integration** (`@IntegrationTest`) | seconds | real Postgres (Testcontainers) | Locking, constraints, idempotency, transactions actually hold end-to-end |
| listeners + event registry | **Integration** | seconds | real Postgres | At-least-once delivery and idempotent consumption actually work |
| module boundaries | **Architecture** (`ModularityTests`) | ~2 s | none | No illegal module dependency, no module touching another's tables |

The guiding ratio: **many unit tests, enough integration tests to cover every transactional and locking guarantee.** Do not integration-test what a unit test already proves; do not unit-test (with mocks) a guarantee that only a real database can enforce (a mocked `FOR UPDATE` proves nothing).

## 1. Unit tests — pure domain

The domain is pure, so these need no Spring context and no setup. Every FSM transition — legal and illegal — gets a test. `kz.tiyn.identity.family.FamilyLinkTests` and `kz.tiyn.identity.account.AccountDomainTests` are the references; they sit in the feature's own package so they can reach package-private classes.

```java
@Test
void unlinkThenRelinkBumpsTheGenerationOnceAndEachStepRecordsItsEvent() {
    var link = FamilyLink.between(parent, teen);
    link.link();

    link.unlink(Instant.now());
    link.unlink(Instant.now());
    link.link();

    assertThat(link.generation()).isEqualTo(2);
    assertThat(link.pullEvents()).containsExactly(
            new FamilyLinked(parent.id(), teen.id(), 1),
            new FamilyUnlinked(parent.id(), teen.id(), 1),
            new FamilyLinked(parent.id(), teen.id(), 2));
}
```

Use `@ParameterizedTest` with a `@MethodSource` to exhaust a transition matrix (state × action → result or error).

**Rule:** if a bug could be a wrong guard or a miscalculated reward, there must be a unit test that would have caught it. No database, no mocks, no HTTP.

## 2. Unit tests — service orchestration with fakes

Where a service has branching worth testing without a database, give it a narrow store interface and inject an in-memory fake (a tiny real implementation over a `Map`). Fakes beat call-order assertions because they read like the real thing and survive refactors. Do not add an interface to a store only to enable this; add it when a test needs it.

## 3. Integration tests — real Postgres via Testcontainers

The guarantees that matter most — `FOR UPDATE` serialization, the non-negative check constraint, idempotency uniqueness, transaction atomicity — **cannot** be proven with a mock. They are database behavior, so integration tests run against a real Postgres 16 container.

### The `@IntegrationTest` meta-annotation

`kz.tiyn.support.IntegrationTest` bundles `@SpringBootTest`, the Postgres container (`PostgresTestConfig`, wired with `@ServiceConnection`), a fake Google verifier (`FakeGoogleConfig`), and the required properties (JWT secret, a Google client id, a one-hour tick interval so the scheduler stays quiet). Flyway runs all migrations when the context starts.

```java
@IntegrationTest
@AutoConfigureMockMvc
class IdentityFlowTests { ... }
```

Spring caches contexts, so each distinct context configuration starts one container. Tests that add `@AutoConfigureMockMvc` or other annotations form a separate context and a separate container. That is a few seconds each, once per context; a shared static container is an easy optimisation if it grows.

### Isolation: unique data, no cleanup

Do not clean state with `TRUNCATE` or `DELETE`: `ledger_entries` is append-only and rejects both by design, and cleanup between tests hides ordering bugs. Each test creates its own users and rows with random identifiers (`"sub-" + UUID.randomUUID()`) and asserts only on its own data. Never assert on global counts.

### No slow tests

No test waits more than about 2 seconds. Never `Thread.sleep` to let something happen: inject the `Clock` where the code takes one, shrink intervals through properties, wait on a latch or use Modulith's `Scenario` for asynchronous effects, and use Awaitility with a short timeout only as a ceiling that turns a hang into a failure. Starting the Postgres container costs a few seconds once per context; that is the only fixed cost.

### What integration tests must cover

- **Concurrency on the wallet.** Fire concurrent cash-outs that together exceed the balance; assert exactly one succeeds and the balance never goes negative. This is the single most important test in the codebase — it proves the `FOR UPDATE` + check-constraint design.
- **Optimistic task conflict.** Two concurrent approvals of the same task; assert one wins and one gets a conflict.
- **Idempotency.** Submit the same reward/payout command twice; assert exactly one ledger entry.
- **Transaction atomicity.** Force a failure after the state change but before the event is published (inject an error); assert nothing was committed.
- **Races on unique keys.** `IdentityConcurrencyTests` starts several threads on one latch, through `MockMvc` because services are package-private, against the same Google subject and the same join, then asserts one user and one link in the database. Use it as the template.
- **Event durability.** Publish, make a real listener fail once, resubmit, and assert the effect happened exactly once (`docs/events-outbox.md`, "Testing").

### HTTP-level flow tests

Drive the real HTTP stack with `MockMvc` and a real database behind it, exercising a full scenario from the PRD:

```
link family → parent creates milestone → teen submits proof → parent approves
  → assert ledger credited, assert TaskApproved delivered, assert push enqueued
```

One such black-box test per PRD user scenario (Scenario 1: milestone; Scenario 2: cash-out loop) is the acceptance backbone. `IdentityFlowTests` is the reference for style: helper methods for sign-in and bearer headers, JSON assertions with `jsonPath`.

## 4. Architecture tests

`PaginationPolicyTests` (ADR-0002) fails the build when a `GET` handler returns a `List`, array or `Collection` unless it is annotated `@BoundedList("<why it stays small>")` with a non-blank reason. Every paginated endpoint extends `PagedEndpointContract` in `kz.tiyn.support`, which runs the shared scenarios (full walk, inserts between pages, ties, last and empty page, filter-bound cursors, limit bounds, caller scoping) against it.

`ModularityTests` runs on every build:

- `ApplicationModules.of(TiynApplication.class).verify()` fails on a dependency that crosses a module's internal packages, a cycle between modules, or a reference to a non-exposed type.
- `eachModuleTouchesOnlyItsOwnTables` is an ArchUnit rule: code outside `kz.tiyn.<owner>` may not depend on `kz.tiyn.platform.jooq.<owner>`. Add every new module to `TABLE_OWNERS`.

## Fakes for external systems

- **Google:** `FakeGoogleConfig` supplies a `@Primary` `GoogleVerifier`; build tokens with `FakeGoogleConfig.tokenFor(sub, name)`. The real `JwksGoogleVerifier` has its own unit tests that point it at an unreachable key set and at malformed tokens.
- **Our JWTs:** `TestTokens.sign(...)` builds tokens with any secret, algorithm, issuer, subject, role and expiry, for the negative cases in `PlatformSecurityTests`. Issue valid ones with `AccessTokenIssuer`.
- **S3 / media:** the `media.Storage` interface is faked in service tests. `S3StorageTests` runs the adapter against a SeaweedFS container configured from `docker/seaweedfs-s3.json` (SeaweedFS rejects signed requests without an identity). `ImageSanitizerTests` covers EXIF removal and rotation.
- **FCM / push:** the notification module's FCM client sits behind an interface; assert "a push with this payload to this device" against a fake. `FcmPushSenderTests` checks the message fields and error mapping with a mocked `FirebaseMessaging`. Never call real FCM in tests.

## Coverage expectations

- Aggregates and value objects — near-total line and branch coverage. It is pure logic; there is no excuse.
- Services — every command path and its error branches.
- Integration — every guarantee in `docs/transactions.md`'s checklist and every PRD user scenario.

Coverage percentage is a guide; the JaCoCo floor only stops it from sliding back. a 100%-covered FSM with no concurrency test is worse than an 80%-covered one that proves the wallet can't go negative. Test the guarantees, and coverage follows.

## Commands

```bash
./mvnw verify                       # build, jOOQ codegen, tests, Spotless, Checkstyle, SpotBugs, JaCoCo floor (needs Docker)
./mvnw -Dtest=FamilyLinkTests test  # a single class
./mvnw -Dtest=ModularityTests test  # boundaries only
./mvnw spotless:apply               # format before committing
```

Docker is required for the build itself, not just for tests: jOOQ code generation starts a Postgres container. CI must provide a Docker daemon.

## Build, quality gates and CI

`./mvnw verify` is the single gate, locally and in CI. After the tests it runs, in this order:
- **JaCoCo** report (`target/site/jacoco`) and a coverage floor: line 93%, branch 78%, measured at 95.5% and 81.1% when the gate was added. Raise the floor when coverage rises; never lower it to get a build through. Generated jOOQ code and `TiynApplication` are excluded.
- **Spotless** `check` (Palantir formatting, unused imports).
- **Checkstyle** with `config/checkstyle/checkstyle.xml`. It checks correctness and naming, not formatting (Spotless owns that, and a style-guide rule set would fight it). A `private static final Logger log` is allowed. Generated code is never checked.
- **SpotBugs** at Medium and above, with `config/spotbugs/exclude.xml`: generated jOOQ code, and `EI_EXPOSE_REP`/`EI_EXPOSE_REP2`, which only report that records and constructor-injected beans keep references by design. Add an exclusion only with a reason, and prefer fixing the code.

The build needs Docker (jOOQ code generation and the tests start Postgres), so the jar cannot be built inside `docker build`. The `Dockerfile` only packages the jar that `./mvnw package` produced: `./mvnw -DskipTests package && docker build -t tiyn:local .`. It uses Spring Boot layers, runs as a non-root user and takes all configuration from environment variables; there is no `HEALTHCHECK` (use the actuator `/actuator/health` probes in Kubernetes). Without `TIYN_MEDIA_STORAGE` and `TIYN_NOTIFICATION_SENDER` the container refuses to start, by design.

`.github/workflows/ci.yml` runs `./mvnw verify` on every pull request and on pushes to `main`, uploads the coverage report and the jar, and builds the image. Only a push to `main` publishes `ghcr.io/<owner>/tiyn:<sha>` and `:latest` (using `GITHUB_TOKEN`; the owner is lower-cased because GHCR requires it). GHCR is hosted outside Kazakhstan: before real user data, point the image step at the registry chosen for the production environment. Branch protection ("require CI to pass") is a GitHub setting, not part of the workflow.

