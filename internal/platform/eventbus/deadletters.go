package eventbus

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"
)

type PostgresDeadLetters struct {
	pool *pgxpool.Pool
}

func NewPostgresDeadLetters(pool *pgxpool.Pool) *PostgresDeadLetters {
	return &PostgresDeadLetters{pool: pool}
}

func (d *PostgresDeadLetters) Add(ctx context.Context, letter DeadLetter) error {
	metadata, err := json.Marshal(letter.Metadata)
	if err != nil {
		return fmt.Errorf("marshal dead letter metadata: %w", err)
	}
	_, err = d.pool.Exec(ctx,
		`INSERT INTO platform.dead_letters (topic, message_uuid, payload, metadata, reason)
		 VALUES ($1, $2, $3, $4, $5)`,
		letter.Topic, letter.MessageUUID, letter.Payload, metadata, letter.Reason)
	if err != nil {
		return fmt.Errorf("insert dead letter: %w", err)
	}
	return nil
}
