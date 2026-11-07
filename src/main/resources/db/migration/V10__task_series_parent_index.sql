CREATE INDEX task_series_parent_created_idx ON task.task_series (parent_id, created_at DESC);
