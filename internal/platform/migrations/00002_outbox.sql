-- +goose Up
CREATE TABLE platform.outbox_events (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type      TEXT        NOT NULL,
    aggregate_id    UUID        NOT NULL,
    payload         JSONB       NOT NULL,
    idempotency_key TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_type, idempotency_key)
);

CREATE INDEX outbox_events_pending_idx
    ON platform.outbox_events (id)
    WHERE published_at IS NULL;

CREATE INDEX outbox_events_aggregate_pending_idx
    ON platform.outbox_events (aggregate_id, id)
    WHERE published_at IS NULL;

-- +goose Down
DROP TABLE platform.outbox_events;
