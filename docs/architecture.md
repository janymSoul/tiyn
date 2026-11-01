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

## Layers inside a module

Each module is a vertical slice with the same internal shape. Dependencies point **inward**: web → app → domain; store is called by app; nothing calls web.

```
kz.tiyn.<module>/
  <Api>.java           # root package = the module's public contract:
  <Event>.java         #   interfaces other modules may call, event records it publishes
  domain/              # entities + FSM + value objects. PURE. No jOOQ/Spring web imports.
  app/                 # the service: transactions, orchestration, event publishing
  store/               # jOOQ repositories; persistence only
  web/                 # controllers + request/response records + the module's exception mapping
  <acl>/               # anti-corruption adapters for external systems (e.g. identity.google)
```

**Why this shape.** Modulith exposes only a module's root package; every sub-package is internal. The `domain` package is pure so FSMs are trivially unit-testable. The `app` service is the deep module: a small interface over substantial logic (transaction orchestration, FSM invocation, event emission). `store`, `domain`, `app` and `web` are implementation details hidden from the rest of the system.

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
