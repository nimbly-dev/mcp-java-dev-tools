CREATE TABLE IF NOT EXISTS legacy_backfill_audits (
  legacy_backfill_audit_pk INTEGER PRIMARY KEY,
  legacy_backfill_import_pk INTEGER NOT NULL,
  entry_index INTEGER NOT NULL,
  plan_name TEXT,
  run_id TEXT,
  reason_code TEXT NOT NULL,
  missing_fields_json TEXT,
  violated_fields_json TEXT,
  conflicting_fields_json TEXT,
  created_at_epoch_ms INTEGER NOT NULL,
  FOREIGN KEY (legacy_backfill_import_pk)
    REFERENCES legacy_backfill_imports(legacy_backfill_import_pk)
    ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_legacy_backfill_audits_import
  ON legacy_backfill_audits (legacy_backfill_import_pk, entry_index);
