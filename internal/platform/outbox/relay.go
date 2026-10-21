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
	"tiyn/internal/platform/eventbus"
)

type Publisher interface {
	Publish(ctx context.Context, topic string, msg *message.Message) error
}

type RelayConfig struct {
	BatchSize       int
	DeliveryTimeout time.Duration
	MaxBackoff      time.Duration
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
		if err := r.deliver(ctx, id); err != nil {
			r.logDeliveryFailure(id, err)
		}
	}
	return nil
}

func (r *Relay) deliverableIDs(ctx context.Context) ([]int64, error) {
	rows, err := r.store.Pool().Query(ctx,
		`SELECT o.id FROM platform.outbox_events o
		 WHERE o.published_at IS NULL
		   AND o.next_attempt_at <= now()
		   AND NOT EXISTS (
		       SELECT 1 FROM platform.outbox_events earlier
		       WHERE earlier.aggregate_id = o.aggregate_id
		         AND earlier.id < o.id
		         AND earlier.published_at IS NULL)
		 ORDER BY o.id LIMIT $1`, r.config.BatchSize)
	if err != nil {
		return nil, fmt.Errorf("select deliverable outbox ids: %w", err)
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
		deliveryCtx, cancel := context.WithTimeout(ctx, r.config.DeliveryTimeout)
		defer cancel()
		if err := r.publisher.Publish(deliveryCtx, event.eventType, toMessage(event)); err != nil {
			return err
		}
		_, err = tx.Exec(ctx, `UPDATE platform.outbox_events SET published_at = now() WHERE id = $1`, id)
		return err
	})
	if publishErr == nil || !countsAsFailure(ctx, publishErr) {
		return publishErr
	}
	return errors.Join(publishErr, r.recordFailure(ctx, id))
}

func countsAsFailure(parent context.Context, err error) bool {
	return parent.Err() == nil && !errors.Is(err, eventbus.ErrNoHandler)
}

func (r *Relay) logDeliveryFailure(id int64, err error) {
	if errors.Is(err, eventbus.ErrNoHandler) {
		r.logger.Warn("outbox event has no handler yet", "outbox_id", id, "err", err)
		return
	}
	r.logger.Error("outbox delivery failed", "outbox_id", id, "err", err)
}

func lockForDelivery(ctx context.Context, tx pgx.Tx, id int64) (pendingEvent, error) {
	var event pendingEvent
	err := tx.QueryRow(ctx,
		`SELECT id, event_type, aggregate_id::text, idempotency_key, payload
		 FROM platform.outbox_events
		 WHERE id = $1 AND published_at IS NULL AND next_attempt_at <= now()
		 FOR UPDATE SKIP LOCKED`, id).
		Scan(&event.id, &event.eventType, &event.aggregateID, &event.idempotencyKey, &event.payload)
	return event, err
}

func (r *Relay) recordFailure(ctx context.Context, id int64) error {
	return r.store.ExecTx(ctx, func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx,
			`UPDATE platform.outbox_events
			 SET attempts = attempts + 1,
			     next_attempt_at = now() + make_interval(secs => LEAST(power(2, attempts), $2::float8))
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
