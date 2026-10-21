package outbox

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strconv"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/jackc/pgx/v5"

	"tiyn/internal/platform/db"
)

type Publisher interface {
	Publish(topic string, messages ...*message.Message) error
}

type Relay struct {
	store       *db.Store
	publisher   Publisher
	batchSize   int
	maxAttempts int
	logger      *slog.Logger
}

func NewRelay(store *db.Store, publisher Publisher, batchSize, maxAttempts int, logger *slog.Logger) *Relay {
	return &Relay{store: store, publisher: publisher, batchSize: batchSize, maxAttempts: maxAttempts, logger: logger}
}

type pendingEvent struct {
	id             int64
	eventType      string
	aggregateID    string
	idempotencyKey string
	payload        []byte
}

func (r *Relay) Tick(ctx context.Context, _ time.Time) error {
	ids, err := r.pendingIDs(ctx)
	if err != nil {
		return err
	}
	for _, id := range ids {
		if err := r.deliver(ctx, id); err != nil {
			r.logger.Error("outbox delivery failed", "outbox_id", id, "err", err)
		}
	}
	return nil
}

func (r *Relay) pendingIDs(ctx context.Context) ([]int64, error) {
	rows, err := r.store.Pool().Query(ctx,
		`SELECT id FROM platform.outbox_events
		 WHERE published_at IS NULL AND failed_at IS NULL
		 ORDER BY id LIMIT $1`, r.batchSize)
	if err != nil {
		return nil, fmt.Errorf("select pending outbox ids: %w", err)
	}
	return pgx.CollectRows(rows, pgx.RowTo[int64])
}

func (r *Relay) deliver(ctx context.Context, id int64) error {
	publishErr := r.store.ExecTx(ctx, func(tx pgx.Tx) error {
		event, err := lockForDelivery(ctx, tx, id)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		if err := r.publisher.Publish(event.eventType, toMessage(event)); err != nil {
			return err
		}
		_, err = tx.Exec(ctx, `UPDATE platform.outbox_events SET published_at = now() WHERE id = $1`, id)
		return err
	})
	if publishErr == nil {
		return nil
	}
	if failureErr := r.recordFailure(ctx, id); failureErr != nil {
		return errors.Join(publishErr, failureErr)
	}
	return publishErr
}

func lockForDelivery(ctx context.Context, tx pgx.Tx, id int64) (pendingEvent, error) {
	var event pendingEvent
	err := tx.QueryRow(ctx,
		`SELECT id, event_type, aggregate_id::text, idempotency_key, payload
		 FROM platform.outbox_events
		 WHERE id = $1 AND published_at IS NULL AND failed_at IS NULL
		 FOR UPDATE SKIP LOCKED`, id).
		Scan(&event.id, &event.eventType, &event.aggregateID, &event.idempotencyKey, &event.payload)
	return event, err
}

func (r *Relay) recordFailure(ctx context.Context, id int64) error {
	return r.store.ExecTx(ctx, func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx,
			`UPDATE platform.outbox_events
			 SET attempts = attempts + 1,
			     failed_at = CASE WHEN attempts + 1 >= $2 THEN now() END
			 WHERE id = $1`, id, r.maxAttempts)
		return err
	})
}

func toMessage(event pendingEvent) *message.Message {
	msg := message.NewMessage(strconv.FormatInt(event.id, 10), event.payload)
	msg.Metadata.Set("event_type", event.eventType)
	msg.Metadata.Set("aggregate_id", event.aggregateID)
	msg.Metadata.Set("idempotency_key", event.idempotencyKey)
	return msg
}
