# Result Authoring Checklist

Use this checklist before finalizing a rendered result.

## Determinism

1. Selected template id is registered in manifest.
2. Column order matches template definition.
3. Row ordering is stable (`step.order`, endpoint tie-break).
4. Placeholder values are consistent (`n/a` where allowed).

## Data Source Integrity

1. Rendering is based on persisted artifacts only.
2. Required artifact files exist and parse correctly.
3. Report fields map to artifact fields without heuristic inference.

## Safety

1. Secret values are not rendered.
2. Existing redactions remain redacted.
3. No additional sensitive reconstruction from surrounding fields.
4. Failed-assertion Actual values render only as `[not persisted]`.
5. Failed-assertion Expected values preserve `[REDACTED]`, escape table
   delimiters, normalize newlines, and obey the configured length bound.

## Memory Gate

1. `Memory (bytes)` appears only when memory metric is contract-defined.
2. Memory column omitted entirely when undefined.

## Fail-Closed

If blocked, return:

1. exact missing/invalid artifact or template id
2. deterministic reason code
3. single next action
