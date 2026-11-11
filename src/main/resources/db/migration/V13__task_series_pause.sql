ALTER TABLE task.task_series
    ADD COLUMN closed  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN version INT     NOT NULL DEFAULT 0;

UPDATE task.task_series SET closed = TRUE WHERE NOT active;
