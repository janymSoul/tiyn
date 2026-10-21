package eventbus_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/stretchr/testify/require"

	"tiyn/internal/platform/eventbus"
)

type discardPublisher struct{}

func (discardPublisher) Publish(string, ...*message.Message) error { return nil }
func (discardPublisher) Close() error                              { return nil }

func newBus(t *testing.T) *eventbus.Bus {
	t.Helper()
	bus, err := eventbus.New(discardPublisher{}, eventbus.Options{
		HandlerMaxRetries:    1,
		RetryInitialInterval: time.Millisecond,
		RetryMaxInterval:     time.Millisecond,
	})
	require.NoError(t, err)
	t.Cleanup(func() { _ = bus.Close() })
	return bus
}

func runUntilStopped(t *testing.T, bus *eventbus.Bus) (cancel func(), stopped <-chan error) {
	t.Helper()
	ctx, cancelCtx := context.WithCancel(context.Background())
	result := make(chan error, 1)
	go func() { result <- bus.Run(ctx) }()
	select {
	case <-bus.Running():
	case <-time.After(5 * time.Second):
		t.Fatal("bus did not start")
	}
	return cancelCtx, result
}

func requireStops(t *testing.T, stopped <-chan error) {
	t.Helper()
	select {
	case err := <-stopped:
		require.NoError(t, err)
	case <-time.After(5 * time.Second):
		t.Fatal("bus did not stop after its context was cancelled")
	}
}

func TestRunStopsWhenContextIsCancelledWithoutHandlers(t *testing.T) {
	bus := newBus(t)
	cancel, stopped := runUntilStopped(t, bus)

	cancel()

	requireStops(t, stopped)
}

func TestRunStopsWhenContextIsCancelledWithHandlers(t *testing.T) {
	bus := newBus(t)
	bus.Handle("noop", "SomeEvent", func(*message.Message) error { return nil })
	cancel, stopped := runUntilStopped(t, bus)

	cancel()

	requireStops(t, stopped)
}

func TestPublishToTopicWithoutHandlerFails(t *testing.T) {
	bus := newBus(t)
	runUntilStopped(t, bus)

	err := bus.Publish("UnknownEvent", message.NewMessage("1", []byte("{}")))

	require.True(t, errors.Is(err, eventbus.ErrNoHandler))
}
