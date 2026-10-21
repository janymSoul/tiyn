package eventbus

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/ThreeDotsLabs/watermill/message/router/middleware"
	"github.com/jackc/pgx/v5/pgxpool"
)

const deadLetterWriteTimeout = 5 * time.Second

type DeadLetters struct {
	pool *pgxpool.Pool
}

func NewDeadLetters(pool *pgxpool.Pool) *DeadLetters {
	return &DeadLetters{pool: pool}
}

func (d *DeadLetters) Publish(_ string, messages ...*message.Message) error {
	ctx, cancel := context.WithTimeout(context.Background(), deadLetterWriteTimeout)
	defer cancel()
	for _, msg := range messages {
		metadata, err := json.Marshal(msg.Metadata)
		if err != nil {
			return fmt.Errorf("marshal dead letter metadata: %w", err)
		}
		_, err = d.pool.Exec(ctx,
			`INSERT INTO platform.dead_letters (topic, message_uuid, payload, metadata, reason)
			 VALUES ($1, $2, $3, $4, $5)`,
			msg.Metadata.Get(middleware.PoisonedTopicKey), msg.UUID, []byte(msg.Payload), metadata,
			msg.Metadata.Get(middleware.ReasonForPoisonedKey))
		if err != nil {
			return fmt.Errorf("insert dead letter: %w", err)
		}
	}
	return nil
}

func (d *DeadLetters) Close() error {
	return nil
}
