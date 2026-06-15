# Performance Craft Authoring Checklist

Use this checklist before finalizing a crafted plan.

## Determinism

1. At least one required `Strict Line Key` is present.
2. Entrypoint transport facts are proven.
3. `loadModel.mode` is supported.
4. Threshold fields are explicit and measurable.

## Safety

1. No secrets are persisted.
2. No speculative route or target values are written.

## Consistency

1. `metadata.json` and `contract.json` both identify `performance`.
2. `plan.md` mirrors `contract.json`.
3. The observed Java target is represented by strict line keys, not duplicated class/method identity fields.

## Fail-Closed

If any required field is missing or ambiguous, return blocked guidance with:

1. exact missing or invalid fields
2. deterministic reason code
3. single next action
