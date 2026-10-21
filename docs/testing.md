# Testing Strategy

Testability is an architectural property here, not an afterthought. The module shape (pure domain, explicit persistence, narrow interfaces) exists partly so that each layer is testable in the cheapest way that still gives a real guarantee. A change is not done until it is tested at the right level.

## The test pyramid, mapped to our layers

| Layer | Test type | Speed | Dependencies | What it proves |
|---|---|---|---|---|
| `domain/` (FSM, value objects) | **Unit** | microseconds | none | Transition guards, invariants, calculations are correct |
| `service.go` (orchestration) | **Unit** w/ fakes | milliseconds | fake store + fake bus | The command sequences the right steps, handles errors, emits the right event |
| `store/` + `service` + `http/` | **Functional** | seconds | real Postgres (Testcontainers) | Locking, constraints, idempotency, transactions actually hold end-to-end |
| outbox relay + consumers | **Functional** | seconds | real Postgres | At-least-once delivery and idempotent consumption actually work |

The guiding ratio: **many unit tests, enough functional tests to cover every transactional and locking guarantee.** Do not functionally test what a unit test already proves; do not unit-test (with mocks) a guarantee that only a real database can enforce (a mocked `FOR UPDATE` proves nothing).

## 1. Unit tests — pure domain

The domain is pure, so these need no setup. Every FSM transition — legal and illegal — gets a test.

```go
func TestTask_Approve_fromSubmitted_succeeds(t *testing.T) {
    task := domain.NewTask(/* ... */)
    task.ForceState(domain.StateSubmitted) // test-only constructor helper
    err := task.Approve(parentID)
    require.NoError(t, err)
    require.Equal(t, domain.StateApproved, task.State())
}

func TestTask_Approve_fromAssigned_isInvalid(t *testing.T) {
    task := domain.NewTask(/* ... */) // starts ASSIGNED
    err := task.Approve(parentID)
    require.ErrorIs(t, err, domain.ErrInvalidTransition)
}
```

Table-driven tests are the idiom for exhausting the transition matrix:

```go
func TestTask_transitions(t *testing.T) {
    cases := []struct {
        from    domain.State
        action  func(*domain.Task) error
        wantErr error
        wantTo  domain.State
    }{
        {domain.StateSubmitted, approve, nil, domain.StateApproved},
        {domain.StateAssigned,  approve, domain.ErrInvalidTransition, domain.StateAssigned},
        // ... every cell of the matrix
    }
    // ...
}
```

**Rule:** if a bug could be a wrong guard or a miscalculated reward, there must be a unit test that would have caught it. No DB, no mocks, no HTTP.

## 2. Unit tests — service orchestration with fakes

The service depends on the store and the event publisher through **interfaces**, so tests inject in-memory fakes. This proves the orchestration logic (did it emit the event? did it handle the store error?) without a database.

```go
type fakeStore struct {
    tasks   map[uuid.UUID]domain.Task
    events  []outbox.Event
    seenIdem map[string]bool
}
// implements the store interface the service depends on; records what happened.
```

Fakes, not mocks-with-expectations. A fake behaves like a tiny real implementation (an in-memory map); it is less brittle than call-order assertions and reads like the real thing. Use a mocking library only where a fake is genuinely more code than it is worth.

> This is only possible because the service depends on an **interface**, not on `*Store` directly. Define the narrow store interface in the module and have the service accept it. Depending on a narrow interface is also what makes the code testable.

## 3. Functional tests — real Postgres via Testcontainers

The guarantees that matter most — `FOR UPDATE` serialization, the non-negative check constraint, idempotency uniqueness, transaction atomicity — **cannot** be proven with a mock. They are database behavior. So functional tests run against a real Postgres 16 container.

### Shared container, migrated once

Spin one container per package (or per test binary), run goose migrations against it, and give each test an isolated schema or a truncate-between-tests helper. Spinning a container per test is too slow; sharing one with clean state per test is the balance.

```go
func setupDB(t *testing.T) *pgxpool.Pool {
    ctx := context.Background()
    pg, err := postgres.Run(ctx, "postgres:16-alpine",
        postgres.WithDatabase("tiyn_test"),
        postgres.WithUsername("test"), postgres.WithPassword("test"),
        testcontainers.WithWaitStrategy(wait.ForListeningPort("5432/tcp")),
    )
    require.NoError(t, err)
    t.Cleanup(func() { _ = pg.Terminate(ctx) })

    dsn, _ := pg.ConnectionString(ctx, "sslmode=disable")
    runGooseMigrations(t, dsn)          // migrate up
    pool, _ := pgxpool.New(ctx, dsn)
    t.Cleanup(pool.Close)
    return pool
}
```

Guard functional tests so a machine without Docker can still run `go test ./internal/...`:

```go
func skipIfNoDocker(t *testing.T) {
    if os.Getenv("TIYN_SKIP_FUNCTIONAL") != "" {
        t.Skip("functional tests disabled")
    }
}
```
CI always runs them; a developer without Docker can opt out and still run the unit suite.

### What functional tests must cover

- **Concurrency on the wallet.** Fire two concurrent cash-outs that together exceed the balance; assert exactly one succeeds and the balance never goes negative. This is the single most important test in the codebase — it proves the `FOR UPDATE` + check-constraint design.
- **Optimistic task conflict.** Two concurrent approvals of the same task; assert one wins and one gets `ErrConcurrentModification`.
- **Idempotency.** Submit the same reward/payout command twice; assert exactly one ledger entry.
- **Transaction atomicity.** Force a failure after the ledger insert but before the outbox insert (inject an error); assert nothing was committed.
- **Outbox + relay.** Commit a command, run the relay, assert the event was published exactly as a pending→published transition; crash the relay mid-batch (simulate) and assert redelivery, then assert the idempotent consumer credits only once.

### HTTP-level functional tests

The top-level flow tests drive real HTTP via `httptest.Server` (or chi's handler + `httptest.NewRecorder`) with a real DB behind it, exercising a full scenario from the PRD:

```
link family → parent creates milestone → teen submits proof → parent approves
  → assert ledger credited, assert TaskApprovedEvent delivered, assert push enqueued
```

One such black-box test per PRD user scenario (Scenario 1: milestone; Scenario 2: cash-out loop) is the acceptance backbone.

## Fakes for external systems

- **S3 / media:** the `media.PresignService` interface is faked in service tests; functional tests can run against a SeaweedFS container (same S3 API) if upload behavior is under test, otherwise fake it — we never handle bytes, so there is little to test beyond key derivation (which is a unit test).
- **FCM / push:** the notification module's FCM client is behind an interface; assert "a push with this payload to this device" against a fake. Never call real FCM in tests.
- **Watermill bus:** in unit tests, fake the publisher. In functional tests, use the real `gochannel` bus so subscribe/publish wiring is exercised.

## Coverage expectations

- `internal/*/domain/` — near-total line + branch coverage. It is pure logic; there is no excuse.
- `internal/*/` service — every command path and its error branches.
- Functional — every guarantee in `docs/transactions.md`'s checklist and every PRD user scenario.

Coverage percentage is a guide, not a target; a 100%-covered FSM with no concurrency test is worse than an 80%-covered one that proves the wallet can't go negative. Test the guarantees, and coverage follows.

## Commands

```bash
go test ./internal/...            # unit + functional for module code (needs Docker for functional)
go test ./test/...                # top-level scenario/acceptance tests
TIYN_SKIP_FUNCTIONAL=1 go test ./... # unit only, no Docker
go test -race ./...               # ALWAYS runnable; the race detector is mandatory in CI
```

The `-race` flag is non-optional in CI: this is a concurrent system (relay goroutine, concurrent HTTP handlers, the event bus). A data race is a bug even if a test passes without `-race`.
