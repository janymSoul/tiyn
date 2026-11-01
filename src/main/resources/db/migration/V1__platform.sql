CREATE SCHEMA platform;

CREATE DOMAIN platform.currency AS CHAR(3) CHECK (VALUE = 'KZT');

CREATE FUNCTION platform.set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
