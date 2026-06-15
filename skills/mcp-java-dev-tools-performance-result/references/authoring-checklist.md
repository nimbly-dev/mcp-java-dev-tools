# Performance Result Authoring Checklist

Use this checklist before finalizing a rendered result.

## Determinism

1. Template id is registered.
2. Threshold fields map directly from Artifacts.
3. Required strict line-hit verdict is derived from Artifact evidence.

## Safety

1. Secret values are not rendered.
2. Existing redactions remain redacted.

## Fail-Closed

If blocked, return:

1. exact missing or invalid Artifact/template id
2. deterministic reason code
3. single next action
