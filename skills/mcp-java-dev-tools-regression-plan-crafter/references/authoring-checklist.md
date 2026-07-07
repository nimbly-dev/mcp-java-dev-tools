# Craft Authoring Checklist

Use this checklist before finalizing a crafted plan.

## Determinism

1. `targets[].selectors.fqcn` is present for every target.
2. Multi-module ambiguity is disambiguated via `sourceRoot` and/or signature.
3. `steps[].order` is unique and sequential from `1..N`.
4. `steps[].protocol` maps to `steps[].transport.<protocol>`.

## Safety

1. No secrets persisted in defaults.
2. Secret prerequisites are marked `secret=true`.
3. Every prerequisite has deterministic `provisioning` (`user_input` or `discoverable`).
4. Every discoverable prerequisite defines `discoverySource`.
5. No speculative placeholder values that can produce non-actionable failures.

## Runtime Verification

1. `probeVerification`/`pinStrictProbeKey` values match requested behavior.
2. If pinning is enabled, strict key format is `FQCN#method:line`.

## Consistency

1. `plan.md` steps mirror `contract.json` steps.
2. Step IDs and order match between human and machine artifacts.
3. Expectations are measurable and deterministic.
4. `metadata.execution.discoveryPolicy` matches prerequisite provisioning strategy.
5. Optional `watchers[]` depend only on prior steps and verify downstream completion/readiness.
6. Optional `externalVerification[]` verify downstream data validity with provider-matched requests.
7. Context interpolation uses canonical `${key}` placeholders.

## Secret Boundaries

1. External verification credentials or connection details are not persisted as plan defaults.
2. HTTP/SQL verification contracts keep secret-bearing configuration in runtime/project-owned context.

## Fail-Closed

If any required field is missing or ambiguous, return blocked guidance with:

1. exact missing/invalid fields
2. deterministic reason code
3. single next action
