CREATE TABLE platform.idempotency_keys (
    user_id      UUID        NOT NULL,
    key          TEXT        NOT NULL,
    operation    TEXT        NOT NULL,
    request_hash TEXT        NOT NULL,
    resource_id  UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key)
);

CREATE INDEX idempotency_keys_created_idx ON platform.idempotency_keys (created_at);
