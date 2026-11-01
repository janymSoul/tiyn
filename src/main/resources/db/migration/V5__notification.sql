CREATE SCHEMA notification;

CREATE TABLE notification.device_tokens (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL,
    token        TEXT        NOT NULL UNIQUE,
    platform     TEXT        NOT NULL,
    locale       TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX device_tokens_user_idx ON notification.device_tokens (user_id);

CREATE TABLE notification.delivered (
    event_key    TEXT        NOT NULL,
    recipient_id UUID        NOT NULL,
    delivered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_key, recipient_id)
);

CREATE INDEX delivered_delivered_at_idx ON notification.delivered (delivered_at);
