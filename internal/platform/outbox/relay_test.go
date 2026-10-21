package outbox_test

import (
	"context"
	"errors"
	"log/slog"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/require"

	"tiyn/internal/platform/db"
	"tiyn/internal/platform/eventbus"
	"tiyn/internal/platform/outbox"
	"tiyn/internal/platform/testdb"
)

const approvedEvent = "TaskApprovedEvent"

func TestMain(m *testing.M) {
	testdb.Main(m)
}

type flakyDeadLetters struct {
	inner   eventbus.DeadLetterStore
	failing atomic.Bool
}

func (f *flakyDeadLetters) Add(ctx context.Context, letter eventbus.DeadLetter) error {
	if f.failing.Load() {
		return errors.New("dead letter store unavailable")
	}
	return f.inner.Add(ctx, letter)
}

type harness struct {
	pool        *pgxpool.Pool
	store       *db.Store
	bus         *eventbus.Bus
	deadLetters *flakyDeadLetters
	relay       *outbox.Relay
}

func newHarness(t *testing.T, handlerMaxRetries int, handlerTimeout time.Duration) *harness {
	t.Helper()
	pool := testdb.New(t)
	store := db.NewStore(pool)
	deadLetters := &flakyDeadLetters{inner: eventbus.NewPostgresDeadLetters(pool)}
	bus := eventbus.New(deadLetters, eventbus.Options{
		HandlerMaxRetries:    handlerMaxRetries,
		HandlerTimeout:       handlerTimeout,
		RetryInitialInterval: time.Millisecond,
		RetryMaxInterval:     5 * time.Millisecond,
	})
	return &harness{
		pool:        pool,
		store:       store,
		bus:         bus,
		deadLetters: deadLetters,
		relay:       outbox.NewRelay(store, bus, outbox.RelayConfig{BatchSize: 50, MaxBackoff: time.Minute}, slog.New(slog.DiscardHandler)),
	}
}

func (h *harness) handle(t *testing.T, name string, handler eventbus.Handler) {
	t.Helper()
	require.NoError(t, h.bus.Handle(name, approvedEvent, handler))
}

func (h *harness) enqueue(t *testing.T, key string) uuid.UUID {
	t.Helper()
	aggregateID := uuid.New()
	h.enqueueFor(t, aggregateID, key)
	return aggregateID
}

func (h *harness) enqueueFor(t *testing.T, aggregateID uuid.UUID, key string) {
	t.Helper()
	h.enqueueTyped(t, approvedEvent, aggregateID, key)
}

func (h *harness) enqueueTyped(t *testing.T, eventType string, aggregateID uuid.UUID, key string) {
	t.Helper()
	err := h.store.ExecTx(context.Background(), func(tx pgx.Tx) error {
		return outbox.Insert(context.Background(), tx, outbox.Event{
			Type:           eventType,
			AggregateID:    aggregateID,
			IdempotencyKey: key,
			Payload:        map[string]string{"key": key},
		})
	})
	require.NoError(t, err)
}

func (h *harness) tick(t *testing.T) {
	t.Helper()
	require.NoError(t, h.relay.Tick(context.Background(), time.Now()))
}

func (h *harness) makeDue(t *testing.T) {
	t.Helper()
	_, err := h.pool.Exec(context.Background(), `UPDATE platform.outbox_events SET next_attempt_at = now()`)
	require.NoError(t, err)
}

func (h *harness) count(t *testing.T, table, condition string) int {
	t.Helper()
	var n int
	err := h.pool.QueryRow(context.Background(), "SELECT count(*) FROM "+table+" WHERE "+condition).Scan(&n)
	require.NoError(t, err)
	return n
}

func (h *harness) published(t *testing.T) int {
	return h.count(t, "platform.outbox_events", "published_at IS NOT NULL")
}

func (h *harness) pendingWithAttempts(t *testing.T, attempts int) int {
	return h.count(t, "platform.outbox_events", "published_at IS NULL AND attempts = "+itoa(attempts))
}

func itoa(n int) string {
	return string(rune('0' + n))
}

func keysOf(received *[]string, mu *sync.Mutex) func() []string {
	return func() []string {
		mu.Lock()
		defer mu.Unlock()
		return append([]string(nil), *received...)
	}
}

func TestRelayDeliversPendingEventToHandler(t *testing.T) {
	h := newHarness(t, 1, time.Second)
	var received []string
	h.handle(t, "collect", func(msg *message.Message) error {
		received = append(received, msg.Metadata.Get("idempotency_key"))
		return nil
	})
	h.enqueue(t, "REWARD-TASK-1")

	h.tick(t)

	require.Equal(t, []string{"REWARD-TASK-1"}, received)
	require.Equal(t, 1, h.published(t))
}

func TestHandlerFailingOnceStillDeliversTheEvent(t *testing.T) {
	h := newHarness(t, 3, time.Second)
	var calls atomic.Int32
	h.handle(t, "flaky", func(*message.Message) error {
		if calls.Add(1) == 1 {
			return errors.New("transient failure")
		}
		return nil
	})
	h.enqueue(t, "REWARD-TASK-1")

	h.tick(t)

	require.Equal(t, int32(2), calls.Load())
	require.Equal(t, 1, h.published(t))
	require.Equal(t, 0, h.count(t, "platform.dead_letters", "TRUE"))
}

func TestHandlerAlwaysFailingIsDeadLetteredAndTheNextEventStillFlows(t *testing.T) {
	h := newHarness(t, 1, time.Second)
	var received []string
	h.handle(t, "poisoned", func(msg *message.Message) error {
		key := msg.Metadata.Get("idempotency_key")
		if key == "REWARD-TASK-1" {
			return errors.New("permanent failure")
		}
		received = append(received, key)
		return nil
	})
	h.enqueue(t, "REWARD-TASK-1")
	h.enqueue(t, "REWARD-TASK-2")

	h.tick(t)

	require.Equal(t, []string{"REWARD-TASK-2"}, received)
	require.Equal(t, 2, h.published(t))
	require.Equal(t, 1, h.count(t, "platform.dead_letters",
		"reason LIKE '%permanent failure%' AND topic = '"+approvedEvent+"' AND metadata->>'handler' = 'poisoned'"))
}

func TestFailureToRecordADeadLetterKeepsTheEventPendingWithBackoffAndRecovers(t *testing.T) {
	h := newHarness(t, 0, time.Second)
	var calls atomic.Int32
	h.handle(t, "poisoned", func(*message.Message) error {
		if calls.Add(1) <= 1 {
			return errors.New("permanent failure")
		}
		return nil
	})
	h.deadLetters.failing.Store(true)
	h.enqueue(t, "REWARD-TASK-1")

	h.tick(t)

	require.Equal(t, int32(1), calls.Load())
	require.Equal(t, 0, h.published(t))
	require.Equal(t, 1, h.pendingWithAttempts(t, 1))
	require.Equal(t, 1, h.count(t, "platform.outbox_events", "next_attempt_at > now()"))

	h.tick(t)
	require.Equal(t, int32(1), calls.Load())

	h.deadLetters.failing.Store(false)
	h.makeDue(t)
	h.tick(t)
	require.Equal(t, int32(2), calls.Load())
	require.Equal(t, 1, h.published(t))
}

func TestEventWithoutHandlerStaysPendingWithoutCountingAnAttemptUntilAHandlerAppears(t *testing.T) {
	h := newHarness(t, 1, time.Second)
	h.enqueue(t, "REWARD-TASK-1")

	for range 10 {
		h.tick(t)
	}

	require.Equal(t, 0, h.published(t))
	require.Equal(t, 1, h.pendingWithAttempts(t, 0))

	var received atomic.Int32
	h.handle(t, "late", func(*message.Message) error { received.Add(1); return nil })
	h.tick(t)

	require.Equal(t, int32(1), received.Load())
	require.Equal(t, 1, h.published(t))
}

func TestCancelledContextDuringDeliveryIsNotCountedAsAFailure(t *testing.T) {
	h := newHarness(t, 1, time.Minute)
	started := make(chan struct{})
	var calls atomic.Int32
	h.handle(t, "blocking", func(msg *message.Message) error {
		if calls.Add(1) == 1 {
			close(started)
			<-msg.Context().Done()
			return msg.Context().Err()
		}
		return nil
	})
	h.enqueue(t, "REWARD-TASK-1")
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- h.relay.Tick(ctx, time.Now()) }()
	<-started

	cancel()

	select {
	case err := <-done:
		require.NoError(t, err)
	case <-time.After(5 * time.Second):
		t.Fatal("tick did not stop after its context was cancelled")
	}
	require.Equal(t, 0, h.published(t))
	require.Equal(t, 1, h.pendingWithAttempts(t, 0))
	require.Equal(t, 0, h.count(t, "platform.dead_letters", "TRUE"))

	h.tick(t)
	require.Equal(t, 1, h.published(t))
}

type logCapture struct {
	mu      sync.Mutex
	entries []string
}

func (c *logCapture) Enabled(context.Context, slog.Level) bool { return true }
func (c *logCapture) WithAttrs([]slog.Attr) slog.Handler       { return c }
func (c *logCapture) WithGroup(string) slog.Handler            { return c }
func (c *logCapture) Handle(_ context.Context, record slog.Record) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.entries = append(c.entries, record.Message)
	return nil
}

func (c *logCapture) count(message string) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for _, entry := range c.entries {
		if entry == message {
			n++
		}
	}
	return n
}

func TestEventsWaitingForAHandlerAreReportedAtMostOncePerMinute(t *testing.T) {
	h := newHarness(t, 0, time.Second)
	capture := &logCapture{}
	relay := outbox.NewRelay(h.store, h.bus, outbox.RelayConfig{BatchSize: 50, MaxBackoff: time.Minute}, slog.New(capture))
	h.handle(t, "collect", func(*message.Message) error { return nil })
	h.enqueueTyped(t, "OrphanEvent", uuid.New(), "ORPHAN-1")
	h.enqueueTyped(t, "OrphanEvent", uuid.New(), "ORPHAN-2")
	start := time.Now()
	const warning = "outbox events waiting for a handler"

	require.NoError(t, relay.Tick(context.Background(), start))
	require.NoError(t, relay.Tick(context.Background(), start.Add(30*time.Second)))
	require.Equal(t, 1, capture.count(warning))

	require.NoError(t, relay.Tick(context.Background(), start.Add(61*time.Second)))
	require.Equal(t, 2, capture.count(warning))
}

func TestNothingIsReportedWhenEveryPendingEventHasAHandler(t *testing.T) {
	h := newHarness(t, 0, time.Second)
	capture := &logCapture{}
	relay := outbox.NewRelay(h.store, h.bus, outbox.RelayConfig{BatchSize: 50, MaxBackoff: time.Minute}, slog.New(capture))
	h.handle(t, "collect", func(*message.Message) error { return nil })
	h.enqueue(t, "REWARD-TASK-1")

	require.NoError(t, relay.Tick(context.Background(), time.Now()))

	require.Equal(t, 0, capture.count("outbox events waiting for a handler"))
}

func TestEventsAreReportedEvenWhenNoHandlerIsRegisteredAtAll(t *testing.T) {
	h := newHarness(t, 0, time.Second)
	capture := &logCapture{}
	relay := outbox.NewRelay(h.store, h.bus, outbox.RelayConfig{BatchSize: 50, MaxBackoff: time.Minute}, slog.New(capture))
	h.enqueue(t, "REWARD-TASK-1")

	require.NoError(t, relay.Tick(context.Background(), time.Now()))

	require.Equal(t, 1, capture.count("outbox events waiting for a handler"))
}

func TestCleanShutdownDuringDeliveryLogsNoDeliveryError(t *testing.T) {
	h := newHarness(t, 1, time.Minute)
	capture := &logCapture{}
	relay := outbox.NewRelay(h.store, h.bus, outbox.RelayConfig{BatchSize: 50, MaxBackoff: time.Minute}, slog.New(capture))
	started := make(chan struct{})
	h.handle(t, "blocking", func(msg *message.Message) error {
		close(started)
		<-msg.Context().Done()
		return msg.Context().Err()
	})
	h.enqueue(t, "REWARD-TASK-1")
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- relay.Tick(ctx, time.Now()) }()
	<-started

	cancel()
	require.NoError(t, <-done)

	capture.mu.Lock()
	defer capture.mu.Unlock()
	require.Empty(t, capture.entries)
}

func TestHungHandlerIsCutOffRetriedAndDeadLetteredWithoutHoldingLocks(t *testing.T) {
	h := newHarness(t, 1, 50*time.Millisecond)
	var calls atomic.Int32
	h.handle(t, "hung", func(msg *message.Message) error {
		calls.Add(1)
		<-msg.Context().Done()
		return msg.Context().Err()
	})
	h.enqueue(t, "REWARD-TASK-1")

	h.tick(t)

	require.Equal(t, int32(2), calls.Load())
	require.Equal(t, 1, h.published(t))
	require.Equal(t, 1, h.count(t, "platform.dead_letters", "handler = 'hung' AND reason LIKE '%deadline exceeded%'"))
	tx, err := h.pool.Begin(context.Background())
	require.NoError(t, err)
	defer tx.Rollback(context.Background())
	var id int64
	require.NoError(t, tx.QueryRow(context.Background(),
		`SELECT id FROM platform.outbox_events FOR UPDATE NOWAIT`).Scan(&id))
}

func TestEventsWithoutAHandlerDoNotStarveEventsThatHaveOne(t *testing.T) {
	h := newHarness(t, 1, time.Second)
	var received atomic.Int32
	h.handle(t, "collect", func(*message.Message) error { received.Add(1); return nil })
	for i := range 60 {
		h.enqueueTyped(t, "OrphanEvent", uuid.New(), "ORPHAN-"+strconv.Itoa(i))
	}
	h.enqueue(t, "REWARD-TASK-1")

	h.tick(t)

	require.Equal(t, int32(1), received.Load())
	require.Equal(t, 1, h.published(t))
	require.Equal(t, 60, h.count(t, "platform.outbox_events", "published_at IS NULL AND attempts = 0"))
}

func TestOrphanEventsAreDeliveredOnceTheirHandlerIsRegistered(t *testing.T) {
	h := newHarness(t, 1, time.Second)
	h.handle(t, "collect", func(*message.Message) error { return nil })
	h.enqueueTyped(t, "OrphanEvent", uuid.New(), "ORPHAN-1")
	h.tick(t)
	require.Equal(t, 0, h.published(t))

	var received atomic.Int32
	require.NoError(t, h.bus.Handle("late", "OrphanEvent", func(*message.Message) error { received.Add(1); return nil }))
	h.tick(t)

	require.Equal(t, int32(1), received.Load())
	require.Equal(t, 1, h.published(t))
}

func TestEventIsMarkedPublishedWhenShutdownArrivesRightAfterTheHandlerSucceeds(t *testing.T) {
	h := newHarness(t, 1, time.Second)
	ctx, cancel := context.WithCancel(context.Background())
	var calls atomic.Int32
	h.handle(t, "succeeds-then-shutdown", func(*message.Message) error {
		calls.Add(1)
		cancel()
		return nil
	})
	h.enqueue(t, "REWARD-TASK-1")

	require.NoError(t, h.relay.Tick(ctx, time.Now()))

	require.Equal(t, int32(1), calls.Load())
	require.Equal(t, 1, h.published(t))
}

func TestDuplicateDeadLetterForTheSameMessageAndHandlerIsStoredOnce(t *testing.T) {
	h := newHarness(t, 0, time.Second)
	store := eventbus.NewPostgresDeadLetters(h.pool)
	letter := eventbus.DeadLetter{
		Topic: approvedEvent, Handler: "ledger", MessageUUID: "7", Payload: []byte("{}"),
		Metadata: map[string]string{}, Reason: "failure",
	}

	require.NoError(t, store.Add(context.Background(), letter))
	require.NoError(t, store.Add(context.Background(), letter))
	letter.Handler = "notification"
	require.NoError(t, store.Add(context.Background(), letter))

	require.Equal(t, 2, h.count(t, "platform.dead_letters", "message_uuid = '7'"))
}

func TestFailedEventIsNotRetriedBeforeItsBackoffElapses(t *testing.T) {
	h := newHarness(t, 0, time.Second)
	h.deadLetters.failing.Store(true)
	var calls atomic.Int32
	h.handle(t, "poisoned", func(*message.Message) error { calls.Add(1); return errors.New("failure") })
	h.enqueue(t, "REWARD-TASK-1")

	h.tick(t)
	h.tick(t)
	h.tick(t)

	require.Equal(t, int32(1), calls.Load())
}

func TestBackoffGrowsWithEachFailureAndIsCapped(t *testing.T) {
	h := newHarness(t, 0, time.Second)
	h.deadLetters.failing.Store(true)
	h.handle(t, "poisoned", func(*message.Message) error { return errors.New("failure") })
	h.enqueue(t, "REWARD-TASK-1")

	var delays []float64
	for range 8 {
		h.makeDue(t)
		h.tick(t)
		var seconds float64
		require.NoError(t, h.pool.QueryRow(context.Background(),
			`SELECT extract(epoch FROM next_attempt_at - now()) FROM platform.outbox_events`).Scan(&seconds))
		delays = append(delays, seconds)
	}

	require.InDelta(t, 1, delays[0], 1)
	require.InDelta(t, 2, delays[1], 1)
	require.InDelta(t, 4, delays[2], 1)
	require.InDelta(t, 8, delays[3], 1)
	require.LessOrEqual(t, delays[7], 60.0)
}

func TestEventsOfOneAggregateStayInOrderWhenAnEarlierOneIsBackedOff(t *testing.T) {
	h := newHarness(t, 0, time.Second)
	var mu sync.Mutex
	var received []string
	failFirst := atomic.Bool{}
	failFirst.Store(true)
	h.handle(t, "collect", func(msg *message.Message) error {
		key := msg.Metadata.Get("idempotency_key")
		if key == "X-1" && failFirst.Load() {
			return errors.New("failure")
		}
		mu.Lock()
		received = append(received, key)
		mu.Unlock()
		return nil
	})
	h.deadLetters.failing.Store(true)
	aggregateX := uuid.New()
	h.enqueueFor(t, aggregateX, "X-1")
	h.enqueueFor(t, aggregateX, "X-2")
	h.enqueue(t, "Y-1")

	h.tick(t)

	got := keysOf(&received, &mu)
	require.Equal(t, []string{"Y-1"}, got())

	failFirst.Store(false)
	h.makeDue(t)
	h.tick(t)

	require.Equal(t, []string{"Y-1", "X-1"}, got())
	h.tick(t)
	require.Equal(t, []string{"Y-1", "X-1", "X-2"}, got())
}

func TestOrderingIsEnforcedAgainWhenTheEventIsLockedNotOnlyWhenSelected(t *testing.T) {
	h := newHarness(t, 0, time.Second)
	var received atomic.Int32
	h.handle(t, "collect", func(*message.Message) error { received.Add(1); return nil })
	aggregate := uuid.New()
	h.enqueueFor(t, aggregate, "X-1")
	h.enqueueFor(t, aggregate, "X-2")
	var secondID int64
	require.NoError(t, h.pool.QueryRow(context.Background(),
		`SELECT id FROM platform.outbox_events WHERE idempotency_key = 'X-2'`).Scan(&secondID))

	require.NoError(t, outbox.DeliverForTest(h.relay, context.Background(), secondID))

	require.Equal(t, int32(0), received.Load())
	require.Equal(t, 0, h.published(t))
}

func TestEventWithoutAHandlerDoesNotBlockLaterEventsOfTheSameAggregate(t *testing.T) {
	h := newHarness(t, 0, time.Second)
	var received []string
	h.handle(t, "collect", func(msg *message.Message) error {
		received = append(received, msg.Metadata.Get("idempotency_key"))
		return nil
	})
	aggregate := uuid.New()
	h.enqueueTyped(t, "TaskSubmittedEvent", aggregate, "SUBMITTED-1")
	h.enqueueFor(t, aggregate, "APPROVED-1")

	h.tick(t)

	require.Equal(t, []string{"APPROVED-1"}, received)
	require.Equal(t, 1, h.count(t, "platform.outbox_events", "idempotency_key = 'SUBMITTED-1' AND published_at IS NULL"))
}

func TestBackoffStillAdvancesAfterThousandsOfAttempts(t *testing.T) {
	h := newHarness(t, 0, time.Second)
	h.deadLetters.failing.Store(true)
	h.handle(t, "poisoned", func(*message.Message) error { return errors.New("failure") })
	h.enqueue(t, "REWARD-TASK-1")
	_, err := h.pool.Exec(context.Background(), `UPDATE platform.outbox_events SET attempts = 5000`)
	require.NoError(t, err)

	h.tick(t)

	require.Equal(t, 1, h.count(t, "platform.outbox_events", "attempts = 5001 AND next_attempt_at > now()"))
}

func TestEventsAreDeliveredInInsertionOrder(t *testing.T) {
	h := newHarness(t, 1, time.Second)
	var received []string
	h.handle(t, "collect", func(msg *message.Message) error {
		received = append(received, msg.Metadata.Get("idempotency_key"))
		return nil
	})
	for _, key := range []string{"A", "B", "C", "D"} {
		h.enqueue(t, key)
	}

	h.tick(t)

	require.Equal(t, []string{"A", "B", "C", "D"}, received)
}

func TestPendingEventSurvivesUntilARelayRuns(t *testing.T) {
	h := newHarness(t, 1, time.Second)
	h.enqueue(t, "REWARD-TASK-1")
	require.Equal(t, 0, h.published(t))

	var received atomic.Int32
	h.handle(t, "collect", func(*message.Message) error { received.Add(1); return nil })
	h.tick(t)

	require.Equal(t, int32(1), received.Load())
}

func TestConcurrentRelaysDeliverEachEventOnce(t *testing.T) {
	h := newHarness(t, 1, time.Second)
	var mu sync.Mutex
	counts := map[string]int{}
	h.handle(t, "collect", func(msg *message.Message) error {
		mu.Lock()
		counts[msg.Metadata.Get("idempotency_key")]++
		mu.Unlock()
		return nil
	})
	keys := []string{"A", "B", "C", "D", "E", "F"}
	for _, key := range keys {
		h.enqueue(t, key)
	}
	other := outbox.NewRelay(h.store, h.bus, outbox.RelayConfig{BatchSize: 50, MaxBackoff: time.Minute}, slog.New(slog.DiscardHandler))

	var wg sync.WaitGroup
	for _, relay := range []*outbox.Relay{h.relay, other} {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_ = relay.Tick(context.Background(), time.Now())
		}()
	}
	wg.Wait()

	for _, key := range keys {
		require.Equal(t, 1, counts[key], key)
	}
	require.Equal(t, len(keys), h.published(t))
}

func TestRelayDoesNotWaitBehindARowAnotherRelayHasLocked(t *testing.T) {
	h := newHarness(t, 1, time.Second)
	var mu sync.Mutex
	var received []string
	h.handle(t, "collect", func(msg *message.Message) error {
		mu.Lock()
		received = append(received, msg.Metadata.Get("idempotency_key"))
		mu.Unlock()
		return nil
	})
	h.enqueue(t, "LOCKED")
	h.enqueue(t, "FREE")
	holder, err := h.pool.Begin(context.Background())
	require.NoError(t, err)
	_, err = holder.Exec(context.Background(),
		`SELECT id FROM platform.outbox_events WHERE idempotency_key = 'LOCKED' FOR UPDATE`)
	require.NoError(t, err)

	done := make(chan error, 1)
	go func() { done <- h.relay.Tick(context.Background(), time.Now()) }()

	select {
	case err := <-done:
		require.NoError(t, err)
	case <-time.After(3 * time.Second):
		_ = holder.Rollback(context.Background())
		t.Fatal("relay waited behind a row locked by another relay")
	}
	require.Equal(t, []string{"FREE"}, received)

	require.NoError(t, holder.Rollback(context.Background()))
	h.tick(t)
	require.Equal(t, []string{"FREE", "LOCKED"}, received)
}

func TestEventWrittenInRolledBackTransactionIsNotQueued(t *testing.T) {
	h := newHarness(t, 1, time.Second)
	boom := errors.New("business failure")

	err := h.store.ExecTx(context.Background(), func(tx pgx.Tx) error {
		if err := outbox.Insert(context.Background(), tx, outbox.Event{
			Type: approvedEvent, AggregateID: uuid.New(), IdempotencyKey: "K", Payload: map[string]string{},
		}); err != nil {
			return err
		}
		return boom
	})

	require.ErrorIs(t, err, boom)
	require.Equal(t, 0, h.count(t, "platform.outbox_events", "TRUE"))
}

func TestDuplicateEventKeyIsRejected(t *testing.T) {
	h := newHarness(t, 1, time.Second)
	h.enqueue(t, "REWARD-TASK-1")

	err := h.store.ExecTx(context.Background(), func(tx pgx.Tx) error {
		return outbox.Insert(context.Background(), tx, outbox.Event{
			Type: approvedEvent, AggregateID: uuid.New(), IdempotencyKey: "REWARD-TASK-1", Payload: map[string]string{},
		})
	})

	require.Error(t, err)
	require.True(t, db.IsUniqueViolation(errors.Unwrap(err)))
}
