-- +goose Up
CREATE SCHEMA identity;

CREATE TABLE identity.users (
    id           UUID PRIMARY KEY,
    google_sub   TEXT        NOT NULL UNIQUE,
    role         TEXT        NOT NULL,
    join_code    TEXT UNIQUE,
    display_name TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER users_set_updated_at
    BEFORE UPDATE ON identity.users
    FOR EACH ROW EXECUTE FUNCTION platform.set_updated_at();

CREATE TABLE identity.family_links (
    parent_id  UUID        NOT NULL REFERENCES identity.users (id),
    teen_id    UUID        NOT NULL REFERENCES identity.users (id),
    generation INT         NOT NULL DEFAULT 1 CHECK (generation > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at TIMESTAMPTZ,
    PRIMARY KEY (parent_id, teen_id),
    CHECK (parent_id <> teen_id)
);

CREATE TRIGGER family_links_set_updated_at
    BEFORE UPDATE ON identity.family_links
    FOR EACH ROW EXECUTE FUNCTION platform.set_updated_at();

CREATE INDEX family_links_active_teen_idx ON identity.family_links (teen_id) WHERE removed_at IS NULL;

-- +goose Down
DROP SCHEMA identity CASCADE;
