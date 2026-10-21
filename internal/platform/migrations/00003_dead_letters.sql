-- +goose Up
CREATE TABLE platform.dead_letters (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    topic        TEXT        NOT NULL,
    handler      TEXT        NOT NULL,
    message_uuid TEXT        NOT NULL,
    payload      BYTEA       NOT NULL,
    metadata     JSONB       NOT NULL,
    reason       TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    redriven_at  TIMESTAMPTZ,
    UNIQUE (message_uuid, handler)
);

CREATE INDEX dead_letters_open_idx ON platform.dead_letters (id) WHERE redriven_at IS NULL;

-- +goose Down
DROP TABLE platform.dead_letters;
