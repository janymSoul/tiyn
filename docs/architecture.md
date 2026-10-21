# Architecture Overview

## What tiyn is

A dual-interface mobile app (Flutter client, Go backend) for the Kazakhstan market:

- **Parents** create tasks and academic milestones, review photo proof, approve/reject/request redo, cancel tasks, and fulfil payouts through the bank app of their choice via a deep link.
- **Teens** complete chores and milestones, upload proof, watch a gamified ledger balance grow, and request payouts.

Money never moves through our backend. We keep a **virtual ledger** and orchestrate a manual P2P transfer through the parent's own bank app via deep links. Kaspi comes first; other Kazakhstani banks (Freedom, Forte, and so on) are added when the integration is cheap, as entries in a client-side bank registry. The backend is bank-agnostic. This keeps us out of banking-licence and payment-regulation scope (see PRD §4.3, architecture doc §2). The backend's job is correctness of the ledger, reliability of notifications, and integrity of the audit trail — not payment processing.

## Shape: modular monolith, event-driven internally

One deployable Go binary, internally partitioned into **bounded-context modules** that communicate through narrow interfaces and asynchronous domain events. Chosen for zero network latency between modules, local ACID transactions, and a clean seam to extract a module into its own service later if scale demands it.

```
┌─────────────────────────────────────────────────────────────┐
│  Flutter client (Parent Command Center / Teen Gamified Hub)   │
└───────────────┬───────────────────────────┬──────────────────┘
                │ HTTPS REST (JWT)           │ pre-signed GET (review only)
                ▼                            ▼
┌───────────────────────────────┐   ┌──────────────────────────┐
│   tiyn API (Go)               │   │   Object storage (S3 API) │
│                               │   │   SeaweedFS (KZ)          │
│  ┌─────────────────────────┐  │   └──────────────────────────┘
│  │ internal/ modules       │  │
│  │  identity · task ·      │  │        async push
│  │  ledger · media ·       │  │   ┌──────────────────────────┐
│  │  notification           │  │──▶│  FCM / APNs               │
│  └───────────┬─────────────┘  │   └──────────────────────────┘
│              │ in-process event dispatcher (eventbus.Bus)    │
│              │ + transactional outbox (outbox_events table)    │
└──────────────┼────────────────┘
               ▼
      ┌────────────────────┐
      │  PostgreSQL 16     │  append-only ledger_entries, FOR UPDATE wallets,
      │  (hosted in KZ)    │  outbox_events, per-module tables
      └────────────────────┘
```

## Layers inside a module

Each module is a vertical slice with the same internal shape. Dependencies point **inward**: HTTP → service → domain; store is called by service; nothing calls HTTP.

```
internal/<module>/
  service.go   # the module's deep facade — the only type other wiring touches
  domain/      # entities + FSM + value objects. PURE. No db/http/bus imports.
  store/       # sqlc-generated code + hand-written .sql; persistence only
  http/        # chi handlers: decode → validate → call service → encode
  events.go    # exported event payload types this module PUBLISHES (its contract)
  <module>.go  # the exported interface(s) this module OFFERS to others (its contract)
```

**Why this shape.** The `domain/` package is pure so FSMs are trivially unit-testable and free of infrastructure. The `service.go` facade is the deep module: a small interface over substantial logic (transaction orchestration, FSM invocation, event emission). `store/` and `domain/` and `http/` are implementation details hidden from the rest of the system.

## Technology choices

| Concern | Choice | Rationale |
|---|---|---|
| Language / runtime | Go 1.23+ | Fast startup, single static binary, low memory. |
| HTTP router | `chi` | Minimal, idiomatic, stdlib-compatible `http.Handler`; no framework lock-in. |
| DB driver | `pgx/v5` (via `pgxpool`) | Fastest, most complete Postgres driver; first-class `FOR UPDATE`, listen/notify. |
| Query layer | `sqlc` | Generates type-safe Go from hand-written SQL. We control every query, including locking. No ORM — see below. |
| Migrations | `goose` | Simple, versioned, SQL-first; one migration dir per module. |
| Events | Small in-process dispatcher (`eventbus`) over the Watermill message type | Synchronous delivery with retries and durable dead letters; handlers keep the Watermill signature so a broker can replace it on extraction. |
| Validation | `go-playground/validator` | Struct-tag DTO validation at the HTTP edge. |
| Auth | `golang-jwt/jwt/v5` + chi middleware | JWT issue/verify in the identity module; middleware injects the security context. |
| Object storage | `aws-sdk-go-v2` (S3) | Backend stores proof images (validated, EXIF-stripped) and issues pre-signed GET URLs for review. |
| Push | Firebase Admin SDK (FCM) | Notification module only; APNs bridged by FCM. |
| Config | env vars + `koanf` (or stdlib) | 12-factor; no secrets in code. |
| Functional tests | `testcontainers-go` | Real Postgres per test run; honest coverage of locking/constraints. |

### Why no ORM

The domain models are finite state machines and the ledger is an append-only, double-entry-style journal with explicit row locking (`SELECT … FOR UPDATE`) and a non-negative balance check constraint. An ORM's lazy loading, dirty-checking, and implicit flush actively obscure exactly the two things we must control: **when** a row is locked and **when** a transaction commits. `sqlc` gives us type safety over SQL we write by hand, so the locking and the transaction boundary are visible in the code. For a financial ledger, less magic is a feature.

## Deployment posture

- **Kubernetes is the likely platform** (driven by the data-residency constraint below), but nothing in the code depends on it. The binary is a single stateless container behind a load balancer (SSL termination, CORS).
- **Postgres 16** with automated backups and point-in-time recovery. The ledger's correctness depends on the DB, so this tier is non-negotiable managed infrastructure.
- **Time-driven work** runs as idempotent `Ticker`s. A Kubernetes CronJob runs `tiyn tick` every minute for task expiry, recurrence, and payout expiry; the outbox relay is an in-process loop in the API pods for low latency (see `docs/modules.md`, "Time-driven work").
- **Data residency: host in Kazakhstan.** Kazakhstan's personal-data law is understood to require citizens' personal data to be stored on servers inside the country, so the database and object storage live with a Kazakhstan provider. This rules out region-locked managed services (Cloud SQL, GCS, Cloud Run) and favours Kubernetes with Postgres and an S3-compatible store (SeaweedFS). Confirm the legal details, and the status of Google sign-in and FCM token passthrough, with counsel.

## Non-functional requirements that shape the code

- **Immutable audit trail (SYS-01):** `ledger_entries` is append-only; corrections are compensating entries, never updates or deletes. Enforced at the DB layer.
- **Idempotency everywhere (PRD footnote [d]):** rewards, payouts, and file uploads all carry idempotency keys backed by `UNIQUE` constraints. Event consumers handle at-least-once delivery. See `docs/transactions.md` and `docs/events-outbox.md`.
- **PII & privacy:** profiles are private, no social features exposing minors. Store the minimum PII; never log it. No PII in logs, cache keys, or event payloads beyond what a consumer needs.
- **Localization:** RU + KK. User-facing strings (including notification copy) are not hard-coded in domain logic; they live at the edge (notification templates, API responses).
- **Media:** proof images are sent to the API, which validates type and size, strips EXIF/GPS metadata, and stores them. Clients compress before upload to meet the 3 s / 4G target. See `docs/modules.md` (Media Storage) for the trade-off against pre-signed PUT.
