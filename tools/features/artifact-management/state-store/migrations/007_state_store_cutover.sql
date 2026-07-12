CREATE TABLE state_store_cutover (
  project_name TEXT PRIMARY KEY,
  status TEXT NOT NULL CHECK (status IN ('pre_cutover', 'cutover_in_progress', 'cutover_complete')),
  transition_revision INTEGER NOT NULL DEFAULT 0,
  updated_at_epoch_ms INTEGER NOT NULL,
  completed_at_epoch_ms INTEGER,
  reason_code TEXT
);
