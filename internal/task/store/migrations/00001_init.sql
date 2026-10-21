-- +goose Up
CREATE SCHEMA task;

CREATE TABLE task.task_series (
    id            UUID PRIMARY KEY,
    parent_id     UUID              NOT NULL,
    teen_id       UUID              NOT NULL,
    kind          TEXT              NOT NULL,
    title         TEXT              NOT NULL,
    description   TEXT              NOT NULL DEFAULT '',
    reward_amount BIGINT            NOT NULL CHECK (reward_amount > 0),
    currency      platform.currency NOT NULL,
    recurrence    TEXT              NOT NULL,
    timezone      TEXT              NOT NULL,
    due_time      TIME              NOT NULL,
    starts_on     DATE              NOT NULL,
    active        BOOLEAN           NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ       NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ       NOT NULL DEFAULT now()
);

CREATE TRIGGER task_series_set_updated_at
    BEFORE UPDATE ON task.task_series
    FOR EACH ROW EXECUTE FUNCTION platform.set_updated_at();

CREATE TABLE task.tasks (
    id              UUID PRIMARY KEY,
    series_id       UUID REFERENCES task.task_series (id),
    occurrence_date DATE,
    milestone_id    UUID REFERENCES task.tasks (id),
    parent_id       UUID              NOT NULL,
    teen_id         UUID              NOT NULL,
    kind            TEXT              NOT NULL,
    title           TEXT              NOT NULL,
    description     TEXT              NOT NULL DEFAULT '',
    reward_amount   BIGINT            NOT NULL CHECK (reward_amount > 0),
    currency        platform.currency NOT NULL,
    state           TEXT              NOT NULL CHECK (state IN ('ASSIGNED', 'SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    deadline_at     TIMESTAMPTZ       NOT NULL,
    version         INT               NOT NULL DEFAULT 0,
    submitted_at    TIMESTAMPTZ,
    decided_at      TIMESTAMPTZ,
    decided_by      UUID,
    decision_note   TEXT,
    created_at      TIMESTAMPTZ       NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ       NOT NULL DEFAULT now(),
    UNIQUE (series_id, occurrence_date),
    CHECK ((series_id IS NULL) = (occurrence_date IS NULL)),
    CHECK (milestone_id IS NULL OR series_id IS NULL),
    CHECK (milestone_id <> id)
);

CREATE TRIGGER tasks_set_updated_at
    BEFORE UPDATE ON task.tasks
    FOR EACH ROW EXECUTE FUNCTION platform.set_updated_at();

CREATE INDEX tasks_milestone_idx ON task.tasks (milestone_id) WHERE milestone_id IS NOT NULL;
CREATE INDEX tasks_teen_state_idx ON task.tasks (teen_id, state);
CREATE INDEX tasks_parent_submitted_idx ON task.tasks (parent_id) WHERE state = 'SUBMITTED';
CREATE INDEX tasks_assigned_deadline_idx ON task.tasks (deadline_at) WHERE state = 'ASSIGNED';

CREATE TABLE task.task_proofs (
    id         UUID PRIMARY KEY,
    task_id    UUID        NOT NULL REFERENCES task.tasks (id),
    attempt_no INT         NOT NULL CHECK (attempt_no > 0),
    object_key TEXT,
    text_body  TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (task_id, attempt_no),
    CHECK (object_key IS NOT NULL OR text_body IS NOT NULL)
);

CREATE TABLE task.idempotency_keys (
    key        TEXT PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idempotency_keys_created_idx ON task.idempotency_keys (created_at);

-- +goose Down
DROP SCHEMA task CASCADE;
