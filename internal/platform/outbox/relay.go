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
	Publish(ctx context.Context, topic string, msg *message.Message) error
	Topics() []string
}

type RelayConfig struct {
	BatchSize  int
	MaxBackoff time.Duration
}

type Relay struct {
	store     *db.Store
	publisher Publisher
	config    RelayConfig
	logger    *slog.Logger
}

func NewRelay(store *db.Store, publisher Publisher, config RelayConfig, logger *slog.Logger) *Relay {
	return &Relay{store: store, publisher: publisher, config: config, logger: logger}
}

type pendingEvent struct {
	id             int64
	eventType      string
	aggregateID    string
	idempotencyKey string
	payload        []byte
}

func (r *Relay) Tick(ctx context.Context, _ time.Time) error {
	ids, err := r.deliverableIDs(ctx)
	if err != nil {
		return err
	}
	for _, id := range ids {
		if ctx.Err() != nil {
			return nil
		}
		if err := r.deliver(ctx, id); err != nil && ctx.Err() == nil {
			r.logDeliveryFailure(id, err)
		}
	}
	return nil
}

func (r *Relay) deliverableIDs(ctx context.Context) ([]int64, error) {
	topics := r.publisher.Topics()
	if len(topics) == 0 {
		return nil, nil
	}
	rows, err := r.store.Pool().Query(ctx,
		`SELECT o.id FROM platform.outbox_events o
		 WHERE o.published_at IS NULL
		   AND o.next_attempt_at <= now()
		   AND o.event_type = ANY($2)
		   AND NOT EXISTS (
		       SELECT 1 FROM platform.outbox_events earlier
		       WHERE earlier.aggregate_id = o.aggregate_id
		         AND earlier.id < o.id
		         AND earlier.event_type = ANY($2)
		         AND earlier.published_at IS NULL)
		 ORDER BY o.id LIMIT $1`, r.config.BatchSize, topics)
	if err != nil {
		return nil, fmt.Errorf("select deliverable outbox ids: %w", err)
	}
	return pgx.CollectRows(rows, pgx.RowTo[int64])
}

func (r *Relay) deliver(ctx context.Context, id int64) error {
	txCtx := context.WithoutCancel(ctx)
	topics := r.publisher.Topics()
	publishErr := r.store.ExecTx(txCtx, func(tx pgx.Tx) error {
		event, err := lockForDelivery(txCtx, tx, id, topics)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		if err := r.publisher.Publish(ctx, event.eventType, toMessage(event)); err != nil {
			return err
		}
		_, err = tx.Exec(txCtx, `UPDATE platform.outbox_events SET published_at = now() WHERE id = $1`, id)
		return err
	})
	if publishErr == nil || ctx.Err() != nil {
		return publishErr
	}
	return errors.Join(publishErr, r.recordFailure(ctx, id))
}

func (r *Relay) logDeliveryFailure(id int64, err error) {
	r.logger.Error("outbox delivery failed", "outbox_id", id, "err", err)
}

func lockForDelivery(ctx context.Context, tx pgx.Tx, id int64, topics []string) (pendingEvent, error) {
	var event pendingEvent
	err := tx.QueryRow(ctx,
		`SELECT id, event_type, aggregate_id::text, idempotency_key, payload
		 FROM platform.outbox_events
		 WHERE id = $1 AND published_at IS NULL AND next_attempt_at <= now()
		   AND NOT EXISTS (
		       SELECT 1 FROM platform.outbox_events earlier
		       WHERE earlier.aggregate_id = outbox_events.aggregate_id
		         AND earlier.id < outbox_events.id
		         AND earlier.event_type = ANY($2)
		         AND earlier.published_at IS NULL)
		 FOR UPDATE SKIP LOCKED`, id, topics).
		Scan(&event.id, &event.eventType, &event.aggregateID, &event.idempotencyKey, &event.payload)
	return event, err
}

func (r *Relay) recordFailure(ctx context.Context, id int64) error {
	return r.store.ExecTx(ctx, func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx,
			`UPDATE platform.outbox_events
			 SET attempts = attempts + 1,
			     next_attempt_at = now() + make_interval(secs => LEAST(power(2, LEAST(attempts, 20)), $2::float8))
			 WHERE id = $1`, id, r.config.MaxBackoff.Seconds())
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
