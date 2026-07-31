# Endpoint Table Result

Render markdown table using this exact base layout:

| Endpoint     | Status     | HTTP Code     | Duration (ms)   | Probe Coverage     |
| ------------ | ---------- | ------------- | --------------- | ------------------ |
| `<endpoint>` | `<status>` | `<http_code>` | `<duration_ms>` | `<probe_coverage>` |

When memory metric is explicitly contract-defined, append this column:

| Endpoint     | Status     | HTTP Code     | Duration (ms)   | Probe Coverage     | Memory (bytes)   |
| ------------ | ---------- | ------------- | --------------- | ------------------ | ---------------- |
| `<endpoint>` | `<status>` | `<http_code>` | `<duration_ms>` | `<probe_coverage>` | `<memory_bytes>` |

Deterministic rules:

1. sort by `step.order` ascending
2. tie-break by endpoint text
3. use `n/a` for missing optional fields
4. for blocked/no-step runs, emit one placeholder row:
   - endpoint: `(no executed endpoints)`
5. `Probe Coverage` must use canonical enums:
   - `verified_line_hit`
   - `http_only_unverified_line`
   - `unknown`
   - `n/a` (placeholder row only)

When canonical trigger-step assertion outcomes include `fail` or
`blocked_invalid`, append this section after the endpoint table:

### Failed assertions

|           Step | Endpoint     | Assertion        | Actual Path     | Operator     | Status     | Expected     | Actual            | Reason          |
| -------------: | ------------ | ---------------- | --------------- | ------------ | ---------- | ------------ | ----------------- | --------------- |
| `<step_order>` | `<endpoint>` | `<assertion_id>` | `<actual_path>` | `<operator>` | `<status>` | `<expected>` | `[not persisted]` | `<reason_code>` |

Do not emit the section when no trigger-step assertions failed. Rows sort by
step order, endpoint, and assertion id. Escape `|`, replace embedded newlines
with spaces, limit Expected to 256 rendered characters using `...`, preserve
`[REDACTED]` exactly, and never infer or render an assertion actual value.
