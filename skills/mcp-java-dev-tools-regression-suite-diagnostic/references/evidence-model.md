# Evidence Model

- Canonical per-run Artifacts are authoritative historical execution evidence.
- SQLite is an operational/query projection and is never live Probe truth.
- `projects.json` is policy and configuration context, not run-outcome evidence.
- Live Sidecar/Probe status is authoritative only for current runtime state.
- Watcher, external verification, and Correlation evidence must remain distinct.
- A successful trigger, health check, Watcher, external verification, or unscoped Line Hit does not prove a missing Correlation stage.
- Deprecated shared JSON indexes must never be used as a SQLite query fallback.

Evidence references contain only workspace-relative paths, bounded selectors, source action, and sanitized summaries. Never include secrets, authorization headers, raw response bodies, raw SQL rows, or sensitive correlation values.
