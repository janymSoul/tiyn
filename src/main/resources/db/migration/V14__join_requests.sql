ALTER TABLE identity.users ADD COLUMN join_approval_required BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE identity.join_requests (
    id         UUID        PRIMARY KEY,
    parent_id  UUID        NOT NULL REFERENCES identity.users (id),
    teen_id    UUID        NOT NULL REFERENCES identity.users (id),
    state      TEXT        NOT NULL CHECK (state IN ('PENDING', 'APPROVED', 'DECLINED', 'WITHDRAWN')),
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (parent_id <> teen_id),
    CHECK ((state = 'PENDING') = (decided_at IS NULL))
);

CREATE TRIGGER join_requests_set_updated_at
    BEFORE UPDATE ON identity.join_requests
    FOR EACH ROW EXECUTE FUNCTION platform.set_updated_at();

CREATE UNIQUE INDEX join_requests_one_pending_idx ON identity.join_requests (teen_id, parent_id) WHERE state = 'PENDING';
CREATE INDEX join_requests_parent_pending_created_idx ON identity.join_requests (parent_id, created_at DESC, id DESC) WHERE state = 'PENDING';
