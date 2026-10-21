package outbox

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgconn"
)

type Execer interface {
	Exec(ctx context.Context, sql string, arguments ...any) (pgconn.CommandTag, error)
}

type Event struct {
	Type           string
	AggregateID    uuid.UUID
	IdempotencyKey string
	Payload        any
}

func Insert(ctx context.Context, tx Execer, event Event) error {
	payload, err := json.Marshal(event.Payload)
	if err != nil {
		return fmt.Errorf("marshal %s payload: %w", event.Type, err)
	}
	_, err = tx.Exec(ctx,
		`INSERT INTO platform.outbox_events (event_type, aggregate_id, payload, idempotency_key)
		 VALUES ($1, $2, $3, $4)`,
		event.Type, event.AggregateID, payload, event.IdempotencyKey)
	if err != nil {
		return fmt.Errorf("insert %s outbox event: %w", event.Type, err)
	}
	return nil
}
