package eventbus

import (
	"bytes"
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
	Handler     string
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
	HandlerTimeout       time.Duration
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
	options.HandlerMaxRetries = max(options.HandlerMaxRetries, 0)
	options.RetryInitialInterval = max(options.RetryInitialInterval, 0)
	options.RetryMaxInterval = max(options.RetryMaxInterval, options.RetryInitialInterval)
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

func (b *Bus) Topics() []string {
	b.mu.RLock()
	defer b.mu.RUnlock()
	topics := make([]string, 0, len(b.handlers))
	for topic := range b.handlers {
		topics = append(topics, topic)
	}
	return topics
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
		err := b.attempt(ctx, handle, msg)
		if err == nil || attempt >= b.options.HandlerMaxRetries {
			return err
		}
		if err := sleep(ctx, interval); err != nil {
			return err
		}
		interval = min(interval*2, b.options.RetryMaxInterval)
	}
}

func (b *Bus) attempt(ctx context.Context, handle Handler, msg *message.Message) error {
	if b.options.HandlerTimeout <= 0 {
		return invoke(ctx, handle, msg)
	}
	attemptCtx, cancel := context.WithTimeout(ctx, b.options.HandlerTimeout)
	defer cancel()
	return invoke(attemptCtx, handle, msg)
}

func invoke(ctx context.Context, handle Handler, msg *message.Message) (err error) {
	defer func() {
		if recovered := recover(); recovered != nil {
			err = fmt.Errorf("handler panicked: %v", recovered)
		}
	}()
	delivery := message.NewMessage(msg.UUID, bytes.Clone(msg.Payload))
	for key, value := range msg.Metadata {
		delivery.Metadata.Set(key, value)
	}
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
		Handler:     handlerName,
		MessageUUID: msg.UUID,
		Payload:     msg.Payload,
		Metadata:    metadata,
		Reason:      cause.Error(),
	}
}
