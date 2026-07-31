# Regression Result Spec Rules

This file defines normative rules used by the result skill.

## Artifact Inputs

Required:

1. `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/execution.result.json`
2. `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/evidence.json`

Optional:

1. `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/context.resolved.json`

## Template Contract

1. Every template id MUST be documented in `references/templates/index.md`.
2. Default template id MUST be `endpoint_table_result`.
3. Unknown template ids MUST fail closed.

## Endpoint Table Result

Required columns:

1. `Endpoint`
2. `Status`
3. `HTTP Code`
4. `Duration (ms)`
5. `Probe Coverage`

Allowed `Probe Coverage` enum values:

1. `verified_line_hit` (strict line verification confirmed)
2. `http_only_unverified_line` (HTTP assertions passed without strict line verification)
3. `unknown` (coverage cannot be deterministically mapped)
4. `n/a` (placeholder only for blocked/no-step rows)

`Memory (bytes)`:

1. MUST be shown only when memory metric is explicitly contract-defined.
2. MUST be omitted entirely otherwise.

## Deterministic Rendering

1. Rows sorted by `step.order` ascending.
2. Tie-break by endpoint text.
3. Missing optional fields render stable placeholders (`n/a`).
4. No-step runs render exactly one placeholder row.

## Failed-Assertion Diagnostics

The default template MAY append a `### Failed assertions` section when trigger
step assertions fail. It MUST:

1. inspect only `execution.result.json.steps[].assertions[]`;
2. exclude `pass` and `skipped_optional` statuses;
3. sort rows by `step.order`, endpoint text, then assertion `id`;
4. render `Step`, `Endpoint`, `Assertion`, `Actual Path`, `Operator`, `Status`,
   `Expected`, `Actual`, and `Reason` columns;
5. render Actual as `[not persisted]` without reading or inferring an assertion
   actual value;
6. preserve an Expected value of `[REDACTED]` exactly;
7. replace embedded newlines with spaces, escape `|`, and truncate rendered
   Expected values to 256 characters with `...`; and
8. fail closed when a persisted assertion cannot deterministically map `id`,
   `actualPath`, `operator`, `status`, or `reasonCode`.

Watcher and external-verification assertion diagnostics are not part of this
template because they do not have the endpoint/step identity required here.

## Redaction and Safety

1. Secret values MUST NOT be re-exposed.
2. `[REDACTED]` values from artifacts MUST remain redacted.
3. Renderer MUST fail closed when required fields cannot be mapped deterministically.
