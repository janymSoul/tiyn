package outbox_test

import (
	"context"
	"errors"
	"log/slog"
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

const (
	testMaxAttempts = 3
	approvedEvent   = "TaskApprovedEvent"
)

func TestMain(m *testing.M) {
	testdb.Main(m)
}

type harness struct {
	pool  *pgxpool.Pool
	store *db.Store
	bus   *eventbus.Bus
	relay *outbox.Relay
}

func newHarness(t *testing.T, handlerMaxRetries int) *harness {
	t.Helper()
	pool := testdb.New(t)
	store := db.NewStore(pool)
	bus, err := eventbus.New(eventbus.NewDeadLetters(pool), eventbus.Options{
		HandlerMaxRetries:    handlerMaxRetries,
		RetryInitialInterval: time.Millisecond,
		RetryMaxInterval:     5 * time.Millisecond,
	})
	require.NoError(t, err)

	t.Cleanup(func() { _ = bus.Close() })
	return &harness{
		pool:  pool,
		store: store,
		bus:   bus,
		relay: outbox.NewRelay(store, bus, 50, testMaxAttempts, slog.New(slog.DiscardHandler)),
	}
}

func (h *harness) start(t *testing.T) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	go func() { _ = h.bus.Run(ctx) }()
	select {
	case <-h.bus.Running():
	case <-time.After(5 * time.Second):
		t.Fatal("bus did not start")
	}
}

func (h *harness) enqueue(t *testing.T, key string) {
	t.Helper()
	err := h.store.ExecTx(context.Background(), func(tx pgx.Tx) error {
		return outbox.Insert(context.Background(), tx, outbox.Event{
			Type:           approvedEvent,
			AggregateID:    uuid.New(),
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

func (h *harness) published(t *testing.T) int {
	t.Helper()
	return h.countWhere(t, "platform.outbox_events", "published_at IS NOT NULL")
}

func (h *harness) countWhere(t *testing.T, table, condition string) int {
	t.Helper()
	var n int
	err := h.pool.QueryRow(context.Background(), "SELECT count(*) FROM "+table+" WHERE "+condition).Scan(&n)
	require.NoError(t, err)
	return n
}

func TestRelayDeliversPendingEventToHandler(t *testing.T) {
	h := newHarness(t, 1)
	var received []string
	h.bus.Handle("collect", approvedEvent, func(msg *message.Message) error {
		received = append(received, msg.Metadata.Get("idempotency_key"))
		return nil
	})
	h.start(t)
	h.enqueue(t, "REWARD-TASK-1")

	h.tick(t)

	require.Equal(t, []string{"REWARD-TASK-1"}, received)
	require.Equal(t, 1, h.published(t))
}

func TestHandlerFailingOnceStillDeliversTheEvent(t *testing.T) {
	h := newHarness(t, 3)
	var calls atomic.Int32
	h.bus.Handle("flaky", approvedEvent, func(*message.Message) error {
		if calls.Add(1) == 1 {
			return errors.New("transient failure")
		}
		return nil
	})
	h.start(t)
	h.enqueue(t, "REWARD-TASK-1")

	h.tick(t)

	require.Equal(t, int32(2), calls.Load())
	require.Equal(t, 1, h.published(t))
	require.Equal(t, 0, h.countWhere(t, "platform.dead_letters", "TRUE"))
}

func TestHandlerAlwaysFailingIsDeadLetteredAndRelayMovesOn(t *testing.T) {
	h := newHarness(t, 1)
	var received []string
	h.bus.Handle("poisoned", approvedEvent, func(msg *message.Message) error {
		key := msg.Metadata.Get("idempotency_key")
		if key == "REWARD-TASK-1" {
			return errors.New("permanent failure")
		}
		received = append(received, key)
		return nil
	})
	h.start(t)
	h.enqueue(t, "REWARD-TASK-1")
	h.enqueue(t, "REWARD-TASK-2")

	h.tick(t)

	require.Equal(t, []string{"REWARD-TASK-2"}, received)
	require.Equal(t, 2, h.published(t))
	require.Equal(t, 1, h.countWhere(t, "platform.dead_letters", "reason LIKE '%permanent failure%' AND topic = '"+approvedEvent+"'"))
}

func TestEventWithoutHandlerStaysPendingThenIsParked(t *testing.T) {
	h := newHarness(t, 1)
	h.start(t)
	h.enqueue(t, "REWARD-TASK-1")

	h.tick(t)
	require.Equal(t, 0, h.published(t))
	require.Equal(t, 1, h.countWhere(t, "platform.outbox_events", "attempts = 1 AND failed_at IS NULL"))

	h.tick(t)
	h.tick(t)
	require.Equal(t, 1, h.countWhere(t, "platform.outbox_events", "failed_at IS NOT NULL"))

	h.tick(t)
	require.Equal(t, 1, h.countWhere(t, "platform.outbox_events", "attempts = 3"))
}

func TestEventsAreDeliveredInInsertionOrder(t *testing.T) {
	h := newHarness(t, 1)
	var received []string
	h.bus.Handle("collect", approvedEvent, func(msg *message.Message) error {
		received = append(received, msg.Metadata.Get("idempotency_key"))
		return nil
	})
	h.start(t)
	for _, key := range []string{"A", "B", "C", "D"} {
		h.enqueue(t, key)
	}

	h.tick(t)

	require.Equal(t, []string{"A", "B", "C", "D"}, received)
}

func TestPendingEventSurvivesUntilARelayRuns(t *testing.T) {
	h := newHarness(t, 1)
	h.enqueue(t, "REWARD-TASK-1")
	require.Equal(t, 0, h.published(t))

	var received atomic.Int32
	h.bus.Handle("collect", approvedEvent, func(*message.Message) error {
		received.Add(1)
		return nil
	})
	h.start(t)
	h.tick(t)

	require.Equal(t, int32(1), received.Load())
}

func TestConcurrentRelaysDeliverEachEventOnce(t *testing.T) {
	h := newHarness(t, 1)
	var mu sync.Mutex
	counts := map[string]int{}
	h.bus.Handle("collect", approvedEvent, func(msg *message.Message) error {
		mu.Lock()
		counts[msg.Metadata.Get("idempotency_key")]++
		mu.Unlock()
		return nil
	})
	h.start(t)
	keys := []string{"A", "B", "C", "D", "E", "F"}
	for _, key := range keys {
		h.enqueue(t, key)
	}
	other := outbox.NewRelay(h.store, h.bus, 50, testMaxAttempts, slog.New(slog.DiscardHandler))

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

func TestEventWrittenInRolledBackTransactionIsNotQueued(t *testing.T) {
	h := newHarness(t, 1)
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
	require.Equal(t, 0, h.countWhere(t, "platform.outbox_events", "TRUE"))
}

func TestDuplicateEventKeyIsRejected(t *testing.T) {
	h := newHarness(t, 1)
	h.enqueue(t, "REWARD-TASK-1")

	err := h.store.ExecTx(context.Background(), func(tx pgx.Tx) error {
		return outbox.Insert(context.Background(), tx, outbox.Event{
			Type: approvedEvent, AggregateID: uuid.New(), IdempotencyKey: "REWARD-TASK-1", Payload: map[string]string{},
		})
	})

	require.Error(t, err)
	require.True(t, db.IsUniqueViolation(errors.Unwrap(err)))
}
