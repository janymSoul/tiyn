package eventbus

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/ThreeDotsLabs/watermill"
	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/ThreeDotsLabs/watermill/message/router/middleware"
	"github.com/ThreeDotsLabs/watermill/pubsub/gochannel"
)

var ErrNoHandler = errors.New("no handler registered for topic")

const deadLetterTopic = "dead_letters"

type Options struct {
	HandlerMaxRetries    int
	RetryInitialInterval time.Duration
	RetryMaxInterval     time.Duration
	Logger               watermill.LoggerAdapter
}

type Bus struct {
	pubsub *gochannel.GoChannel
	router *message.Router

	mu     sync.RWMutex
	topics map[string]struct{}
}

func New(deadLetters message.Publisher, opts Options) (*Bus, error) {
	logger := opts.Logger
	if logger == nil {
		logger = watermill.NopLogger{}
	}
	router, err := message.NewRouter(message.RouterConfig{}, logger)
	if err != nil {
		return nil, fmt.Errorf("create router: %w", err)
	}
	poison, err := middleware.PoisonQueue(deadLetters, deadLetterTopic)
	if err != nil {
		return nil, fmt.Errorf("create poison queue: %w", err)
	}
	retry := middleware.Retry{
		MaxRetries:      opts.HandlerMaxRetries,
		InitialInterval: opts.RetryInitialInterval,
		MaxInterval:     opts.RetryMaxInterval,
		Multiplier:      2,
		Logger:          logger,
	}
	router.AddMiddleware(poison, retry.Middleware, middleware.Recoverer)

	return &Bus{
		pubsub: gochannel.NewGoChannel(gochannel.Config{BlockPublishUntilSubscriberAck: true}, logger),
		router: router,
		topics: make(map[string]struct{}),
	}, nil
}

func (b *Bus) Handle(handlerName, topic string, handler message.NoPublishHandlerFunc) {
	b.mu.Lock()
	b.topics[topic] = struct{}{}
	b.mu.Unlock()
	b.router.AddNoPublisherHandler(handlerName, topic, b.pubsub, handler)
}

func (b *Bus) Publish(topic string, messages ...*message.Message) error {
	b.mu.RLock()
	_, registered := b.topics[topic]
	b.mu.RUnlock()
	if !registered {
		return fmt.Errorf("%w: %s", ErrNoHandler, topic)
	}
	return b.pubsub.Publish(topic, messages...)
}

func (b *Bus) Run(ctx context.Context) error {
	done := make(chan error, 1)
	go func() { done <- b.router.Run(ctx) }()

	select {
	case err := <-done:
		return err
	case <-ctx.Done():
		closeErr := b.router.Close()
		return errors.Join(<-done, closeErr)
	}
}

func (b *Bus) Running() <-chan struct{} {
	return b.router.Running()
}

func (b *Bus) Close() error {
	return errors.Join(b.router.Close(), b.pubsub.Close())
}
