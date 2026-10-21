-- +goose Up
CREATE SCHEMA platform;

CREATE DOMAIN platform.currency AS CHAR(3) CHECK (VALUE = 'KZT');

-- +goose StatementBegin
CREATE FUNCTION platform.set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- +goose StatementEnd

-- +goose Down
DROP SCHEMA platform CASCADE;
