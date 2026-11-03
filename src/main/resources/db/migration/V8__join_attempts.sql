CREATE TABLE identity.join_attempts (
    teen_id           UUID        PRIMARY KEY REFERENCES identity.users (id),
    attempts          INT         NOT NULL CHECK (attempts >= 0),
    window_started_at TIMESTAMPTZ NOT NULL
);
