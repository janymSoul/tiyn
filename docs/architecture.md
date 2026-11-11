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
  JoinRequested.java         # public event
  account/                   # feature: users, sign-in, join codes
    AccountService, UserStore, User, JoinCode, AccountController, AccountConfig
                             #   logic classes stay together, package-private (User is public)
    AccountDirectory         #   public interface: what other features may ask of account
    dto/                     #   public request/response records (SignInRequest, UserResponse, …)
    error/                   #   IdentityError, IdentityException, IdentityExceptionHandler
  family/                    # feature: parent-teen links
    FamilyLink, LinkStore, FamilyService, FamilyController, FamilyDirectoryImpl
    JoinRequest, JoinRequestStore, JoinRequestService, JoinRequestController
    dto/                     #   JoinCodeRequest, JoinResponse, JoinPendingResponse
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
| Auth | Spring Security resource server, HS256 JWT | Verification in platform; issuing by `AccessTokenIssuer`; opaque single-use refresh tokens (hash only in `identity.refresh_tokens`) rotate the session; Google ID tokens verified with Spring's `NimbusJwtDecoder` against Google's JWKS. |
| Object storage | AWS SDK v2 (S3) | Backend stores proof images (validated, EXIF-stripped) and issues pre-signed GET URLs for review. |
| Push | Firebase Admin SDK (FCM) | Notification module only; APNs bridged by FCM. |
| Scheduling | Spring `SchedulingConfigurer` + ShedLock (JDBC) | One fixed-delay task and one lock per ticker, so one replica runs each ticker per interval; see `docs/modules.md`, "Time-driven work". |
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
- **Time-driven work** (task expiry, recurrence, payout expiry) runs inside the API pods as one scheduled task per `Ticker`, each under its own ShedLock lock, so one replica runs each ticker per interval. There is no CronJob.
- **Data residency: host in Kazakhstan.** Kazakhstan's personal-data law is understood to require citizens' personal data to be stored on servers inside the country, so the database and object storage live with a Kazakhstan provider. This rules out region-locked managed services (Cloud SQL, GCS, Cloud Run) and favours Kubernetes with Postgres and an S3-compatible store (SeaweedFS). Confirm the legal details, and the status of Google sign-in and FCM token passthrough, with counsel.
- **JVM sizing.** Startup takes seconds and memory is higher than a static binary. Acceptable for always-running pods; size the heap explicitly rather than relying on container defaults.

## Deployment

The chart lives in `deploy/helm/tiyn`. It deploys only the application: a Deployment (non-root, read-only root filesystem, startup, liveness and readiness probes on `/actuator/health/*`, `maxUnavailable: 0`, a 45 s grace period above the 30 s graceful shutdown), a ConfigMap, a Service and an optional Ingress. Configuration is environment variables: non-secret values from `config.*`, secrets from a Secret (`secret.existingSecret` in real environments, `secret.create` only for local use). The chart refuses to render without `image.tag` (use the commit sha), `config.mediaStorage` and `config.notificationSender`, and without `fcm.existingSecret` when the sender is `fcm`, so a half-configured release fails in `helm template`, not at runtime.

Postgres and SeaweedFS are **not part of the application release**. `postgres.enabled` and `seaweedfs.enabled` exist for local clusters only (plain StatefulSets with one disk, no backups) and default to off. In a real environment the chart receives `DATABASE_URL` and the S3 settings of managed services.

**Run it locally** (Docker Desktop Kubernetes): `./mvnw -DskipTests package && docker build -t tiyn:local .`, then `helm upgrade --install tiyn deploy/helm/tiyn -n tiyn --create-namespace -f deploy/helm/tiyn/values-local.yaml --wait`, `kubectl -n tiyn port-forward svc/tiyn 8080:80`, and `helm uninstall tiyn -n tiyn` when done. Verified this way: Flyway migration on a fresh database, a proof upload to the in-cluster object store with EXIF removed, zero failed requests during a rolling restart of two replicas, and a single pod holding the ShedLock `tick` lock.

**Presigned URLs and the S3 endpoint.** A presigned URL is signed for one host, and the client must be able to reach it. A provider's public S3 endpoint works as `S3_ENDPOINT`. For the in-cluster SeaweedFS (`http://<release>-seaweedfs:8333`, unreachable from outside) set `config.s3.publicEndpoint` (`S3_PUBLIC_ENDPOINT`) to the public URL and enable `seaweedfs.ingress` with that host: the app uploads through the internal endpoint and signs view URLs for the public one. The ingress must forward the original `Host` header, or signature verification fails.

**Real server, step by step** (the staging environment below follows these steps on GCP; no Kazakhstan host exists yet):
1. Pick a host; for real user data a Kazakhstan provider. Two vCPU and 4 GB RAM is the floor for k3s plus the application.
2. Install k3s on one node (it brings Traefik and local-path storage) or use the provider's managed Kubernetes.
3. With a domain, point an A record at the server, open ports 80 and 443, install cert-manager and a Let's Encrypt `ClusterIssuer`, and set `ingress.enabled`, `ingress.host` and `ingress.tlsSecret`. Android blocks plain HTTP by default, so the Flutter app needs this.
4. Provision Postgres outside the release (managed, or its own server) with automated backups; create the database and user.
5. Provision S3-compatible storage with a public endpoint; create the bucket (the application refuses to start without it) and credentials.
6. `kubectl create secret generic tiyn-secrets` with `JWT_SECRET` (at least 32 bytes), `GOOGLE_CLIENT_IDS`, `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`; for `fcm`, a second secret holding the service-account JSON under `credentials.json`.
7. Set the GHCR package to public (GitHub, Packages, package settings) or add `image.pullSecrets`, then `helm upgrade --install tiyn deploy/helm/tiyn -n tiyn --create-namespace --set secret.existingSecret=tiyn-secrets --set image.tag=<sha> -f values-prod.yaml`, where `values-prod.yaml` sets `config.*` and the ingress.
8. Before real data: backups with a tested restore, alerts on the age of the oldest incomplete `event_publication`, and a registry and object store hosted in Kazakhstan.

### Staging on GCP

Staging runs on two plain Compute Engine VMs in `europe-west3-a`, with fake data only. It is built from portable parts (VMs, k3s, Postgres, SeaweedFS, Let's Encrypt, generic DNS), so moving it to a Kazakhstan host means creating the same two machines there and changing two DNS records. Do not use Cloud SQL, GKE, Cloud Run or Secret Manager: they are exactly what the data-residency section rules out for real users.

| Part | Where | Notes |
|---|---|---|
| App, k3s, SeaweedFS, cert-manager | VM `tiyn-staging` (`e2-medium`, Ubuntu 24.04, 30 GB, static IP) | ports 80 and 443 open to everyone; SSH only through Google's IAP tunnel |
| Postgres 16 | VM `tiyn-db` (`e2-small`, 20 GB, no public IP) | listens on the private address only; `pg_hba.conf` accepts the app VM's address only |
| Hostnames | `api-staging.tiyn.dev`, `s3-staging.tiyn.dev` | Cloudflare DNS, **DNS only** (no proxy), so Let's Encrypt validation reaches the VM |
| Image | `ghcr.io/janymsoul/tiyn:<commit sha>` | public package; CI publishes it on merge to `main` |

**Create the machines.**
1. `gcloud compute addresses create tiyn-staging-ip --region=europe-west3`.
2. Firewall rules on the default network: `tiyn-web` (tcp 80, 443, tag `tiyn-staging`), `tiyn-ssh-iap` (tcp 22 from `35.235.240.0/20`, Google's IAP range, both tags), `tiyn-pg` (tcp 5432 from tag `tiyn-staging` to tag `tiyn-db`). Delete `default-allow-ssh` and `default-allow-rdp`, which are open to the internet. Connect with `gcloud compute ssh <vm> --tunnel-through-iap`: access follows the Google login and not the source IP, which changes with networks and VPNs, and it reaches `tiyn-db`, which has no public address.
3. Create both VMs with `--image-family=ubuntu-2404-lts-amd64 --image-project=ubuntu-os-cloud`, the static address and tag `tiyn-staging` on the app VM, tag `tiyn-db` on the database VM. Give the database VM an external address only while installing packages, then remove it with `gcloud compute instances delete-access-config tiyn-db --access-config-name=external-nat`; Cloud NAT is the alternative if it must stay patchable.
4. In Cloudflare, add two A records pointing at the static address, **DNS only**.

**Postgres on `tiyn-db`.** `apt install postgresql`, then create a login role and a database owned by it, set `listen_addresses = 'localhost,<private ip>'` in a `conf.d` file, add `host tiyn tiyn <app VM private ip>/32 scram-sha-256` to `pg_hba.conf`, and restart. A cron job in `/etc/cron.d` runs `pg_dump -Fc` nightly and deletes dumps older than seven days. Those dumps sit on the same disk, so they do not survive losing the VM; add disk snapshots before the data matters.

**k3s and cert-manager on `tiyn-staging`.** Install k3s (`curl -sfL https://get.k3s.io | sudo sh -`) and Helm, then cert-manager (`helm upgrade --install cert-manager jetstack/cert-manager --version v1.21.3 -n cert-manager --create-namespace --set crds.enabled=true`). Create a `ClusterIssuer` named `letsencrypt-prod` using the ACME production server, the operator's email, and an `http01` solver with `ingressClassName: traefik`. The chart's ingress annotation `cert-manager.io/cluster-issuer: letsencrypt-prod` then gets each hostname a certificate within about a minute.

**Secrets (never in the repository).** Generate them on the operator's machine and create them with `kubectl`:
- `tiyn-secrets` from an env file: `JWT_SECRET` (random, at least 32 bytes), `GOOGLE_CLIENT_IDS` (comma-separated Web, Android and iOS client IDs), `DATABASE_URL` (`jdbc:postgresql://<db private ip>:5432/tiyn`), `DATABASE_USER`, `DATABASE_PASSWORD`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`.
- `tiyn-fcm` from the Firebase service-account file, under the key `credentials.json`.
- The SeaweedFS secret in the chart reads the S3 keys from `secret.values`, so the same two keys also go into a mode-600 values file on the VM that is passed to `helm` with `-f`. Making that secret read from `existingSecret` is a known improvement.

**Deploy.** `deploy/staging/deploy.sh [<sha>]` does this in one step (default: the latest commit on `origin/main`; `--dry-run` checks only). It refuses a commit that has no published image, copies `values-staging.yaml` to the VM, runs the `helm upgrade` below with `--atomic` (a release whose new pod never becomes ready rolls back; database migrations are not undone) and then checks `/actuator/health`. By hand: clone the repository on the VM, check out the commit whose image CI published, and run `helm upgrade --install tiyn deploy/helm/tiyn -n tiyn -f values-staging.yaml -f <s3 keys file> --set image.tag=<sha>`. `values-staging.yaml` selects `s3` storage, the in-cluster SeaweedFS with its own ingress, `config.s3.publicEndpoint=https://s3-staging.tiyn.dev`, the `fcm` sender with `fcm.dryRun: true`, the app ingress for `api-staging.tiyn.dev`, and no in-cluster Postgres.

**Google sign-in.** Create OAuth clients in the Firebase project's Google Cloud project: a Web client (needed by the OAuth Playground and, in the usual Flutter setup, the `serverClientId` of the ID token), an Android client (package name, debug SHA-1) and an iOS client (bundle ID), with the consent screen set to External and Testing, and the test accounts listed. All their IDs go into `GOOGLE_CLIENT_IDS`. A family needs two Google accounts, because one account is one user with one role. Get ID tokens from the OAuth Playground with the Web client, then call `POST /auth/google`.

**Smoke test** (done on 2026-11-01, all passing): health UP over a valid certificate; parent and teen sign in with real Google accounts; the teen joins with the parent's code; the parent creates a task; the teen submits a JPEG carrying GPS and an orientation tag; `GET /tasks/{id}/proofs` returns a URL on `s3-staging.tiyn.dev`; fetching it returns 200 with a rotated image and no EXIF; a tampered signature returns 403. With the `fcm` sender in dry-run mode, a fake device token is answered `UNREGISTERED` by Firebase and removed, which shows the key authenticates and the message is built correctly.

**Not covered yet.** Real push delivery (needs a device token from the Flutter app; then set `fcm.dryRun: false`), disk snapshots, monitoring and alerts, VM cost estimates, and deploying automatically from CI (today the script is run by hand on purpose).

**Moving to a Kazakhstan host.** Create the same two machines there; install k3s, cert-manager and Postgres as above; `pg_dump -Fc` the database and `pg_restore` it; copy the bucket with any S3 client (`aws s3 sync` between the two endpoints); recreate the secrets; run the same `helm upgrade --install`; then change the two DNS records. The Docker image should come from a registry hosted in Kazakhstan by then.

## Non-functional requirements that shape the code

- **Immutable audit trail (SYS-01):** `ledger_entries` is append-only; corrections are compensating entries, never updates or deletes. Enforced at the DB layer by triggers, which also block `TRUNCATE`, so tests cannot reset state that way.
- **Idempotency everywhere (PRD footnote [d]):** rewards, payouts, and file uploads all carry idempotency keys backed by `UNIQUE` constraints. Event consumers handle at-least-once delivery. See `docs/transactions.md` and `docs/events-outbox.md`.
- **PII & privacy:** profiles are private, no social features exposing minors. Store the minimum PII; never log it. No PII in logs, cache keys, or event payloads beyond what a consumer needs.
- **Localization:** RU + KK. User-facing strings (including notification copy) are not hard-coded in domain logic; they live at the edge (notification templates, API responses).
- **Media:** proof images are sent to the API, which validates type and size, strips EXIF/GPS metadata, and stores them. Clients compress before upload to meet the 3 s / 4G target. See `docs/modules.md` (Media Storage) for the trade-off against pre-signed PUT.
