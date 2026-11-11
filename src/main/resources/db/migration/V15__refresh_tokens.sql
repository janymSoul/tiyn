CREATE TABLE identity.refresh_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES identity.users (id),
    family_id  UUID        NOT NULL,
    token_hash TEXT        NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER refresh_tokens_set_updated_at
    BEFORE UPDATE ON identity.refresh_tokens
    FOR EACH ROW EXECUTE FUNCTION platform.set_updated_at();

CREATE INDEX refresh_tokens_family_idx ON identity.refresh_tokens (family_id);
CREATE INDEX refresh_tokens_expires_at_idx ON identity.refresh_tokens (expires_at);
