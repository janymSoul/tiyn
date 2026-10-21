-- +goose Up
CREATE SCHEMA ledger;

CREATE TABLE ledger.wallets (
    teen_id        UUID,
    currency       platform.currency,
    balance_amount BIGINT      NOT NULL DEFAULT 0 CHECK (balance_amount >= 0),
    locked_amount  BIGINT      NOT NULL DEFAULT 0 CHECK (locked_amount >= 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (teen_id, currency),
    CHECK (locked_amount <= balance_amount)
);

CREATE TRIGGER wallets_set_updated_at
    BEFORE UPDATE ON ledger.wallets
    FOR EACH ROW EXECUTE FUNCTION platform.set_updated_at();

CREATE TABLE ledger.ledger_entries (
    id              UUID PRIMARY KEY,
    teen_id         UUID              NOT NULL,
    currency        platform.currency NOT NULL,
    kind            TEXT              NOT NULL CHECK (kind IN ('TASK_REWARD', 'PAYOUT', 'PENALTY', 'GOAL_LOCK', 'GOAL_UNLOCK')),
    balance_delta   BIGINT            NOT NULL,
    locked_delta    BIGINT            NOT NULL DEFAULT 0,
    ref_id          UUID,
    idempotency_key TEXT              NOT NULL UNIQUE,
    note            TEXT,
    actor_id        UUID,
    created_at      TIMESTAMPTZ       NOT NULL DEFAULT now(),
    FOREIGN KEY (teen_id, currency) REFERENCES ledger.wallets (teen_id, currency),
    CHECK (kind = 'PENALTY' OR ref_id IS NOT NULL),
    CHECK (
        CASE kind
            WHEN 'TASK_REWARD' THEN balance_delta > 0 AND locked_delta = 0
            WHEN 'PAYOUT'      THEN balance_delta < 0 AND locked_delta = 0
            WHEN 'PENALTY'     THEN balance_delta < 0 AND locked_delta = 0
            WHEN 'GOAL_LOCK'   THEN balance_delta = 0 AND locked_delta > 0
            WHEN 'GOAL_UNLOCK' THEN balance_delta = 0 AND locked_delta < 0
        END
    )
);

CREATE INDEX ledger_entries_teen_created_idx ON ledger.ledger_entries (teen_id, created_at DESC);
CREATE INDEX ledger_entries_ref_idx ON ledger.ledger_entries (ref_id) WHERE ref_id IS NOT NULL;
CREATE UNIQUE INDEX ledger_entries_one_reward_per_task_idx ON ledger.ledger_entries (ref_id) WHERE kind = 'TASK_REWARD';
CREATE UNIQUE INDEX ledger_entries_one_debit_per_payout_idx ON ledger.ledger_entries (ref_id) WHERE kind = 'PAYOUT';

-- +goose StatementBegin
CREATE FUNCTION ledger.reject_ledger_entry_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only';
END;
$$ LANGUAGE plpgsql;
-- +goose StatementEnd

CREATE TRIGGER ledger_entries_no_update_delete
    BEFORE UPDATE OR DELETE ON ledger.ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_ledger_entry_mutation();

CREATE TRIGGER ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger.ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_ledger_entry_mutation();

CREATE TABLE ledger.payouts (
    id              UUID PRIMARY KEY,
    teen_id         UUID              NOT NULL,
    currency        platform.currency NOT NULL,
    parent_id       UUID              NOT NULL,
    amount          BIGINT            NOT NULL CHECK (amount > 0),
    state           TEXT              NOT NULL CHECK (state IN ('REQUESTED', 'FULFILLED', 'CANCELLED')),
    idempotency_key TEXT              NOT NULL UNIQUE,
    requested_at    TIMESTAMPTZ       NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ,
    resolved_by     UUID,
    cancel_reason   TEXT,
    updated_at      TIMESTAMPTZ       NOT NULL DEFAULT now(),
    FOREIGN KEY (teen_id, currency) REFERENCES ledger.wallets (teen_id, currency),
    CHECK ((state = 'REQUESTED') = (resolved_at IS NULL)),
    CHECK (state <> 'FULFILLED' OR resolved_by IS NOT NULL),
    CHECK (state <> 'CANCELLED' OR cancel_reason IS NOT NULL)
);

CREATE TRIGGER payouts_set_updated_at
    BEFORE UPDATE ON ledger.payouts
    FOR EACH ROW EXECUTE FUNCTION platform.set_updated_at();

CREATE UNIQUE INDEX payouts_one_open_per_teen_idx ON ledger.payouts (teen_id) WHERE state = 'REQUESTED';
CREATE INDEX payouts_parent_open_idx ON ledger.payouts (parent_id) WHERE state = 'REQUESTED';
CREATE INDEX payouts_stale_idx ON ledger.payouts (requested_at) WHERE state = 'REQUESTED';

CREATE TABLE ledger.savings_goals (
    id            UUID PRIMARY KEY,
    teen_id       UUID              NOT NULL,
    currency      platform.currency NOT NULL,
    name          TEXT              NOT NULL,
    target_amount BIGINT            NOT NULL CHECK (target_amount > 0),
    state         TEXT              NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ       NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ       NOT NULL DEFAULT now(),
    FOREIGN KEY (teen_id, currency) REFERENCES ledger.wallets (teen_id, currency)
);

CREATE TRIGGER savings_goals_set_updated_at
    BEFORE UPDATE ON ledger.savings_goals
    FOR EACH ROW EXECUTE FUNCTION platform.set_updated_at();

CREATE INDEX savings_goals_teen_idx ON ledger.savings_goals (teen_id);

-- +goose Down
DROP SCHEMA ledger CASCADE;
