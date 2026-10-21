package eventbus_test

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/stretchr/testify/require"

	"tiyn/internal/platform/eventbus"
)

const topic = "TaskApprovedEvent"

type memoryDeadLetters struct {
	mu      sync.Mutex
	letters []eventbus.DeadLetter
	err     error
}

func (m *memoryDeadLetters) Add(_ context.Context, letter eventbus.DeadLetter) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.err != nil {
		return m.err
	}
	m.letters = append(m.letters, letter)
	return nil
}

func (m *memoryDeadLetters) all() []eventbus.DeadLetter {
	m.mu.Lock()
	defer m.mu.Unlock()
	return append([]eventbus.DeadLetter(nil), m.letters...)
}

func newBus(retries int) (*eventbus.Bus, *memoryDeadLetters) {
	deadLetters := &memoryDeadLetters{}
	bus := eventbus.New(deadLetters, eventbus.Options{
		HandlerMaxRetries:    retries,
		RetryInitialInterval: time.Millisecond,
		RetryMaxInterval:     2 * time.Millisecond,
	})
	return bus, deadLetters
}

func newMessage() *message.Message {
	msg := message.NewMessage("1", []byte(`{"reward":100}`))
	msg.Metadata.Set("idempotency_key", "REWARD-TASK-1")
	return msg
}

func mustHandle(t *testing.T, bus *eventbus.Bus, name string, handler eventbus.Handler) {
	t.Helper()
	require.NoError(t, bus.Handle(name, topic, handler))
}

func TestPublishRunsEveryHandlerOfTheTopicInRegistrationOrder(t *testing.T) {
	bus, _ := newBus(1)
	var order []string
	mustHandle(t, bus, "ledger", func(*message.Message) error { order = append(order, "ledger"); return nil })
	mustHandle(t, bus, "notification", func(*message.Message) error { order = append(order, "notification"); return nil })

	err := bus.Publish(context.Background(), topic, newMessage())

	require.NoError(t, err)
	require.Equal(t, []string{"ledger", "notification"}, order)
}

func TestHandlerReceivesPayloadAndMetadataWithoutSharingMutations(t *testing.T) {
	bus, _ := newBus(1)
	msg := newMessage()
	mustHandle(t, bus, "mutating", func(m *message.Message) error {
		m.Metadata.Set("idempotency_key", "changed")
		return nil
	})

	require.NoError(t, bus.Publish(context.Background(), topic, msg))

	require.Equal(t, "REWARD-TASK-1", msg.Metadata.Get("idempotency_key"))
}

func TestHandlerFailingOnceIsRetriedAndNothingIsDeadLettered(t *testing.T) {
	bus, deadLetters := newBus(3)
	calls := 0
	mustHandle(t, bus, "flaky", func(*message.Message) error {
		calls++
		if calls == 1 {
			return errors.New("transient")
		}
		return nil
	})

	err := bus.Publish(context.Background(), topic, newMessage())

	require.NoError(t, err)
	require.Equal(t, 2, calls)
	require.Empty(t, deadLetters.all())
}

func TestPanickingHandlerIsRecoveredAndRetried(t *testing.T) {
	bus, deadLetters := newBus(2)
	calls := 0
	mustHandle(t, bus, "panicky", func(*message.Message) error {
		calls++
		if calls == 1 {
			panic("boom")
		}
		return nil
	})

	err := bus.Publish(context.Background(), topic, newMessage())

	require.NoError(t, err)
	require.Equal(t, 2, calls)
	require.Empty(t, deadLetters.all())
}

func TestHandlerFailingPermanentlyIsDeadLetteredAndPublishSucceeds(t *testing.T) {
	bus, deadLetters := newBus(2)
	calls := 0
	mustHandle(t, bus, "poisoned", func(*message.Message) error {
		calls++
		return errors.New("permanent failure")
	})

	err := bus.Publish(context.Background(), topic, newMessage())

	require.NoError(t, err)
	require.Equal(t, 3, calls)
	letters := deadLetters.all()
	require.Len(t, letters, 1)
	require.Equal(t, topic, letters[0].Topic)
	require.Equal(t, "poisoned", letters[0].Metadata["handler"])
	require.Equal(t, "REWARD-TASK-1", letters[0].Metadata["idempotency_key"])
	require.Contains(t, letters[0].Reason, "permanent failure")
	require.JSONEq(t, `{"reward":100}`, string(letters[0].Payload))
}

func TestHandlerPanickingPermanentlyIsDeadLettered(t *testing.T) {
	bus, deadLetters := newBus(1)
	mustHandle(t, bus, "panicky", func(*message.Message) error { panic("boom") })

	err := bus.Publish(context.Background(), topic, newMessage())

	require.NoError(t, err)
	require.Len(t, deadLetters.all(), 1)
	require.Contains(t, deadLetters.all()[0].Reason, "panicked")
}

func TestOneFailingHandlerDoesNotStopTheOthers(t *testing.T) {
	bus, deadLetters := newBus(0)
	ran := false
	mustHandle(t, bus, "poisoned", func(*message.Message) error { return errors.New("permanent failure") })
	mustHandle(t, bus, "healthy", func(*message.Message) error { ran = true; return nil })

	err := bus.Publish(context.Background(), topic, newMessage())

	require.NoError(t, err)
	require.True(t, ran)
	require.Len(t, deadLetters.all(), 1)
}

func TestPublishFailsWhenTheFailureCannotBeRecorded(t *testing.T) {
	bus, deadLetters := newBus(0)
	deadLetters.err = errors.New("database unavailable")
	calls := 0
	mustHandle(t, bus, "poisoned", func(*message.Message) error { calls++; return errors.New("permanent failure") })

	err := bus.Publish(context.Background(), topic, newMessage())

	require.ErrorContains(t, err, "database unavailable")
	require.Equal(t, 1, calls)
}

func TestPublishToTopicWithoutHandlerFailsWithErrNoHandler(t *testing.T) {
	bus, _ := newBus(1)

	err := bus.Publish(context.Background(), "UnknownEvent", newMessage())

	require.ErrorIs(t, err, eventbus.ErrNoHandler)
}

func TestRegisteringTheSameHandlerNameTwiceFails(t *testing.T) {
	bus, _ := newBus(1)
	mustHandle(t, bus, "ledger", func(*message.Message) error { return nil })

	err := bus.Handle("ledger", topic, func(*message.Message) error { return nil })

	require.ErrorContains(t, err, "already registered")
}

func TestCancelledContextStopsRetriesWithoutDeadLettering(t *testing.T) {
	deadLetters := &memoryDeadLetters{}
	bus := eventbus.New(deadLetters, eventbus.Options{
		HandlerMaxRetries:    5,
		RetryInitialInterval: time.Hour,
		RetryMaxInterval:     time.Hour,
	})
	ctx, cancel := context.WithCancel(context.Background())
	calls := 0
	mustHandle(t, bus, "failing", func(*message.Message) error {
		calls++
		cancel()
		return errors.New("failure during shutdown")
	})

	err := bus.Publish(ctx, topic, newMessage())

	require.ErrorIs(t, err, context.Canceled)
	require.Equal(t, 1, calls)
	require.Empty(t, deadLetters.all())
}

func TestHandlerContextCarriesThePublishContext(t *testing.T) {
	bus, _ := newBus(0)
	ctx, cancel := context.WithCancel(context.Background())
	var seen error
	mustHandle(t, bus, "observer", func(m *message.Message) error {
		cancel()
		seen = m.Context().Err()
		return nil
	})

	require.NoError(t, bus.Publish(ctx, topic, newMessage()))

	require.ErrorIs(t, seen, context.Canceled)
}

func TestHandlerExceedingItsTimeoutIsRetriedThenDeadLettered(t *testing.T) {
	deadLetters := &memoryDeadLetters{}
	bus := eventbus.New(deadLetters, eventbus.Options{
		HandlerMaxRetries:    1,
		HandlerTimeout:       20 * time.Millisecond,
		RetryInitialInterval: time.Millisecond,
		RetryMaxInterval:     time.Millisecond,
	})
	calls := 0
	mustHandle(t, bus, "hung", func(m *message.Message) error {
		calls++
		<-m.Context().Done()
		return m.Context().Err()
	})

	err := bus.Publish(context.Background(), topic, newMessage())

	require.NoError(t, err)
	require.Equal(t, 2, calls)
	require.Len(t, deadLetters.all(), 1)
	require.Equal(t, "hung", deadLetters.all()[0].Handler)
	require.Contains(t, deadLetters.all()[0].Reason, "deadline exceeded")
}

func TestHandlerTimeoutOfZeroMeansNoTimeout(t *testing.T) {
	bus, _ := newBus(0)
	var deadline bool
	mustHandle(t, bus, "observer", func(m *message.Message) error {
		_, deadline = m.Context().Deadline()
		return nil
	})

	require.NoError(t, bus.Publish(context.Background(), topic, newMessage()))

	require.False(t, deadline)
}

func TestTopicsListsEveryTopicWithAHandler(t *testing.T) {
	bus, _ := newBus(0)
	require.Empty(t, bus.Topics())
	require.NoError(t, bus.Handle("a", "TopicA", func(*message.Message) error { return nil }))
	require.NoError(t, bus.Handle("b", "TopicA", func(*message.Message) error { return nil }))
	require.NoError(t, bus.Handle("c", "TopicB", func(*message.Message) error { return nil }))

	require.ElementsMatch(t, []string{"TopicA", "TopicB"}, bus.Topics())
}
