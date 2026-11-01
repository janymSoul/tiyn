# Architecture Overview

## What tiyn is

A dual-interface mobile app (Flutter client, Java backend) for the Kazakhstan market:

- **Parents** create tasks and academic milestones, review photo proof, approve/reject/request redo, cancel tasks, and fulfil payouts through the bank app of their choice via a deep link.
- **Teens** complete chores and milestones, upload proof, watch a gamified ledger balance grow, and request payouts.

Money never moves through our backend. We keep a **virtual ledger** and orchestrate a manual P2P transfer through the parent's own bank app via deep links. Kaspi comes first; other Kazakhstani banks (Freedom, Forte, and so on) are added when the integration is cheap, as entries in a client-side bank registry. The backend is bank-agnostic. This keeps us out of banking-licence and payment-regulation scope (see PRD §4.3, architecture doc §2). The backend's job is correctness of the ledger, reliability of notifications, and integrity of the audit trail — not payment processing.

## Shape: modular monolith, event-driven internally

One deployable Spring Boot application, internally partitioned into **bounded-context modules** that communicate through narrow interfaces and asynchronous domain events. Chosen for zero network latency between modules, local ACID transactions, and a clean seam to extract a module into its own service later if scale demands it. The boundaries are enforced by **Spring Modulith**, not by convention.

```
┌─────────────────────────────────────────────────────────────┐
│  Flutter client (Parent Command Center / Teen Gamified Hub)   │
└───────────────┬───────────────────────────┬──────────────────┘
                │ HTTPS REST (JWT)           │ pre-signed GET (review only)
                ▼                            ▼
┌───────────────────────────────┐   ┌──────────────────────────┐
│   tiyn API (Spring Boot)      │   │   Object storage (S3 API) │
│                               │   │   SeaweedFS (KZ)          │
│  ┌─────────────────────────┐  │   └──────────────────────────┘
│  │ kz.tiyn modules         │  │
│  │  identity · task ·      │  │        async push
│  │  ledger · media ·       │  │   ┌──────────────────────────┐
│  │  notification           │  │──▶│  FCM / APNs               │
│  └───────────┬─────────────┘  │   └──────────────────────────┘
│              │ Spring application events                      │
│              │ + Modulith event publication registry           │
└──────────────┼────────────────┘
               ▼
      ┌────────────────────┐
      │  PostgreSQL 16     │  append-only ledger_entries, FOR UPDATE wallets,
      │  (hosted in KZ)    │  event_publication, per-module schemas
      └────────────────────┘
```

## Structure inside a module

A module is a package under `kz.tiyn`. Its **root package is the public contract**: the interfaces other modules may call and the event records it publishes. Everything else is internal.

Inside a module, sub-packages are **features, not layers**. Each feature package holds its own aggregate, store, service, controller and request/response records, and **those classes are package-private**, so the compiler (not convention) stops one feature's web code from calling another feature's store. Only a small `public` interface crosses between features.

**Folders inside a feature** group only classes that need no package-private access to the logic classes: `dto/` for request and response records, `error/` for the feature's error type and its HTTP mapping. Do not move the service, store, aggregate or controller into folders, because each of those calls the others and would have to become `public`.

```
kz.tiyn.identity/
  FamilyDirectory.java       # public API for other modules
  FamilyLinked.java          # public event
  FamilyUnlinked.java        # public event
  account/                   # feature: users, sign-in, join codes
    AccountService, UserStore, User, JoinCode, AccountController, AccountConfig
                             #   logic classes stay together, package-private (User is public)
    AccountDirectory         #   public interface: what other features may ask of account
    dto/                     #   public request/response records (SignInRequest, UserResponse, …)
    error/                   #   IdentityError, IdentityException, IdentityExceptionHandler
  family/                    # feature: parent-teen links
    FamilyLink, LinkStore, FamilyService, FamilyController, FamilyDirectoryImpl
    dto/                     #   JoinRequest, JoinResponse
  google/                    # anti-corruption layer for an external system
    GoogleVerifier           #   public interface; JwksGoogleVerifier is package-private
```

**Why this shape.** Java has package-private visibility but no per-directory privacy like Go's `internal/`. A layered split (`domain/`, `app/`, `store/`, `web/`) forces every class that crosses a layer to be `public`, so nothing is hidden and the layering is convention only. Splitting by feature keeps the collaborating classes in one package where `package-private` works. Inside a feature, keep the same direction of dependency by habit: controller → service → store and aggregate, the aggregate depending on nothing else. Features depend on each other only through a `public` interface, and the dependency points one way (`family` uses `account`, never the reverse).

A module with a single feature may keep everything in its root package, with only the API types `public`. Introduce feature packages when a module grows two or more aggregates with their own endpoints. Dependencies between features of one module are not checked by Modulith, so keep them acyclic by review.

`kz.tiyn.platform` is the shared kernel, declared an **open** module: JSON error mapping, JWT verification and issuing, `Principal`/`Role`, the ticker scheduler, the `Clock`. It owns no business data and must never depend on a business module.

## Technology choices

| Concern | Choice | Rationale |
|---|---|---|
| Language / runtime | Java 21, Spring Boot 3.5 | Team stack; virtual threads available; mature ecosystem. |
| Module boundaries | Spring Modulith | `ApplicationModules.verify()` fails the build on illegal dependencies; module-scoped tests; event registry. |
| HTTP | Spring MVC (`@RestController`) | Plain, well understood. One `@RestControllerAdvice` in platform plus one per module for module errors. |
| DB access | jOOQ (via `spring-boot-starter-jooq`) | Type-safe SQL we write ourselves, including locking. No ORM — see below. |
| Migrations | Flyway | One ordered history (`V1`…`Vn`) under `src/main/resources/db/migration`, one Postgres schema per module. The same files drive jOOQ code generation. |
| Events | Spring application events + Modulith event publication registry (JDBC) | Events are persisted in the publishing transaction and delivered to `@ApplicationModuleListener`s asynchronously, at-least-once. |
| Validation | Bean Validation at the edge where a generic error is enough; domain factories for rules with a specific error code | Domain invariants are re-checked in the aggregate. |
| Auth | Spring Security resource server, HS256 JWT | Verification in platform; issuing by `AccessTokenIssuer`; Google ID tokens verified with Spring's `NimbusJwtDecoder` against Google's JWKS. |
| Object storage | AWS SDK v2 (S3) | Backend stores proof images (validated, EXIF-stripped) and issues pre-signed GET URLs for review. |
| Push | Firebase Admin SDK (FCM) | Notification module only; APNs bridged by FCM. |
| Scheduling | `@Scheduled` + ShedLock (JDBC) | One replica runs each tick; see `docs/modules.md`, "Time-driven work". |
| Config | `application.yml` bound from env vars, `@ConfigurationProperties` records | 12-factor; secrets never in the repo; invalid config fails startup. |
| Quality | Spotless (Palantir Java Format), ArchUnit, JUnit 5, Testcontainers | `spotless:check` runs in `verify`. |

### Why no ORM

The domain models are finite state machines and the ledger is an append-only, double-entry-style journal with explicit row locking (`SELECT … FOR UPDATE`) and a non-negative balance check constraint. An ORM's lazy loading, dirty-checking, and implicit flush actively obscure exactly the two things we must control: **when** a row is locked and **when** a transaction commits. jOOQ gives us type safety over SQL we write ourselves, so the locking and the transaction boundary are visible in the code. For a financial ledger, less magic is a feature.

### How jOOQ is generated

At `generate-sources`, `testcontainers-jooq-codegen-maven-plugin` starts a throwaway Postgres 16 container, applies the Flyway migrations, reads the live catalog, and writes Java classes to `target/generated-sources/jooq` under `kz.tiyn.platform.jooq.<schema>`. The generated code is not committed. A schema change is a migration, and a column rename shows up as a compile error. **Every build therefore needs Docker.**

All generated classes live under one package tree, which Modulith cannot see as a boundary. `ModularityTests.eachModuleTouchesOnlyItsOwnTables` is the ArchUnit rule that fails the build when a module touches another module's generated tables.

## Deployment posture

- **Kubernetes is the likely platform** (driven by the data-residency constraint below), but nothing in the code depends on it. The application is a single stateless container behind a load balancer (SSL termination, CORS). Several replicas are safe: ticks are guarded by ShedLock and idempotent, and event delivery is guarded by the registry.
- **Postgres 16** with automated backups and point-in-time recovery. The ledger's correctness depends on the DB, so this tier is non-negotiable managed infrastructure.
- **Time-driven work** (task expiry, recurrence, payout expiry) runs inside the API pods through `@Scheduled`, one replica per cycle. There is no CronJob.
- **Data residency: host in Kazakhstan.** Kazakhstan's personal-data law is understood to require citizens' personal data to be stored on servers inside the country, so the database and object storage live with a Kazakhstan provider. This rules out region-locked managed services (Cloud SQL, GCS, Cloud Run) and favours Kubernetes with Postgres and an S3-compatible store (SeaweedFS). Confirm the legal details, and the status of Google sign-in and FCM token passthrough, with counsel.
- **JVM sizing.** Startup takes seconds and memory is higher than a static binary. Acceptable for always-running pods; size the heap explicitly rather than relying on container defaults.

## Non-functional requirements that shape the code

- **Immutable audit trail (SYS-01):** `ledger_entries` is append-only; corrections are compensating entries, never updates or deletes. Enforced at the DB layer by triggers, which also block `TRUNCATE`, so tests cannot reset state that way.
- **Idempotency everywhere (PRD footnote [d]):** rewards, payouts, and file uploads all carry idempotency keys backed by `UNIQUE` constraints. Event consumers handle at-least-once delivery. See `docs/transactions.md` and `docs/events-outbox.md`.
- **PII & privacy:** profiles are private, no social features exposing minors. Store the minimum PII; never log it. No PII in logs, cache keys, or event payloads beyond what a consumer needs.
- **Localization:** RU + KK. User-facing strings (including notification copy) are not hard-coded in domain logic; they live at the edge (notification templates, API responses).
- **Media:** proof images are sent to the API, which validates type and size, strips EXIF/GPS metadata, and stores them. Clients compress before upload to meet the 3 s / 4G target. See `docs/modules.md` (Media Storage) for the trade-off against pre-signed PUT.
