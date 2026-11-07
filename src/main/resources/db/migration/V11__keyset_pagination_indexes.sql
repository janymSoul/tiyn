CREATE INDEX tasks_teen_created_idx ON task.tasks (teen_id, created_at DESC, id DESC);
CREATE INDEX tasks_parent_created_idx ON task.tasks (parent_id, created_at DESC, id DESC);

CREATE INDEX payouts_teen_requested_idx ON ledger.payouts (teen_id, requested_at DESC, id DESC);
CREATE INDEX payouts_parent_requested_idx ON ledger.payouts (parent_id, requested_at DESC, id DESC);

CREATE INDEX savings_goals_teen_created_idx ON ledger.savings_goals (teen_id, created_at DESC, id DESC);
DROP INDEX ledger.savings_goals_teen_idx;

CREATE INDEX ledger_entries_teen_created_id_idx ON ledger.ledger_entries (teen_id, created_at DESC, id DESC);
DROP INDEX ledger.ledger_entries_teen_created_idx;

CREATE INDEX task_series_parent_created_id_idx ON task.task_series (parent_id, created_at DESC, id DESC);
DROP INDEX task.task_series_parent_created_idx;
