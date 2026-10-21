package eventbus

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
)

var ErrNoHandler = errors.New("no handler registered for topic")

type Handler = message.NoPublishHandlerFunc

type DeadLetter struct {
	Topic       string
	MessageUUID string
	Payload     []byte
	Metadata    map[string]string
	Reason      string
}

type DeadLetterStore interface {
	Add(ctx context.Context, letter DeadLetter) error
}

type Options struct {
	HandlerMaxRetries    int
	RetryInitialInterval time.Duration
	RetryMaxInterval     time.Duration
}

type registration struct {
	name   string
	handle Handler
}

type Bus struct {
	deadLetters DeadLetterStore
	options     Options

	mu       sync.RWMutex
	handlers map[string][]registration
}

func New(deadLetters DeadLetterStore, options Options) *Bus {
	return &Bus{deadLetters: deadLetters, options: options, handlers: make(map[string][]registration)}
}

func (b *Bus) Handle(name, topic string, handler Handler) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	for _, existing := range b.handlers[topic] {
		if existing.name == name {
			return fmt.Errorf("handler %q is already registered for %s", name, topic)
		}
	}
	b.handlers[topic] = append(b.handlers[topic], registration{name: name, handle: handler})
	return nil
}

func (b *Bus) Publish(ctx context.Context, topic string, msg *message.Message) error {
	registrations := b.registrations(topic)
	if len(registrations) == 0 {
		return fmt.Errorf("%w: %s", ErrNoHandler, topic)
	}
	for _, registration := range registrations {
		if err := b.dispatch(ctx, topic, registration, msg); err != nil {
			return err
		}
	}
	return nil
}

func (b *Bus) registrations(topic string) []registration {
	b.mu.RLock()
	defer b.mu.RUnlock()
	return append([]registration(nil), b.handlers[topic]...)
}

func (b *Bus) dispatch(ctx context.Context, topic string, registration registration, msg *message.Message) error {
	handleErr := b.handleWithRetries(ctx, registration.handle, msg)
	if handleErr == nil {
		return nil
	}
	if ctx.Err() != nil {
		return ctx.Err()
	}
	return b.deadLetters.Add(ctx, newDeadLetter(topic, registration.name, msg, handleErr))
}

func (b *Bus) handleWithRetries(ctx context.Context, handle Handler, msg *message.Message) error {
	interval := b.options.RetryInitialInterval
	for attempt := 0; ; attempt++ {
		err := invoke(ctx, handle, msg)
		if err == nil || attempt >= b.options.HandlerMaxRetries {
			return err
		}
		if err := sleep(ctx, interval); err != nil {
			return err
		}
		interval = min(interval*2, b.options.RetryMaxInterval)
	}
}

func invoke(ctx context.Context, handle Handler, msg *message.Message) (err error) {
	defer func() {
		if recovered := recover(); recovered != nil {
			err = fmt.Errorf("handler panicked: %v", recovered)
		}
	}()
	delivery := msg.Copy()
	delivery.SetContext(ctx)
	return handle(delivery)
}

func sleep(ctx context.Context, duration time.Duration) error {
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

func newDeadLetter(topic, handlerName string, msg *message.Message, cause error) DeadLetter {
	metadata := make(map[string]string, len(msg.Metadata)+1)
	for key, value := range msg.Metadata {
		metadata[key] = value
	}
	metadata["handler"] = handlerName
	return DeadLetter{
		Topic:       topic,
		MessageUUID: msg.UUID,
		Payload:     msg.Payload,
		Metadata:    metadata,
		Reason:      cause.Error(),
	}
}
