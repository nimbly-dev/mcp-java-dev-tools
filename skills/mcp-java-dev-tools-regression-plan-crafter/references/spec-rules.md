# Regression Plan Spec Rules

This file defines the normative rules used by the craft skill.

## Package Layout

```text
.mcpjvm/<project_name>/plans/regression/<regression_name>/
  metadata.json
  contract.json
  plan.md
  runs/<run_id>/
```

`runs/<run_id>/...` is machine-generated and out of scope for crafting.

## metadata.json (required)

- `specVersion`: string
- `execution.intent`: must be `regression`
- `execution.probeVerification`: boolean
- `execution.pinStrictProbeKey`: boolean
- `execution.discoveryPolicy`: `disabled` | `allow_discoverable_prerequisites`

When `probeVerification=true` and `pinStrictProbeKey=true`, every target must provide a strict probe key.

## contract.json (required)

### targets[]

- `type`: `class_method` | `class_scope` | `module_scope`
- `selectors.fqcn`: required
- `selectors.method`: optional
- `selectors.signature`: optional
- `selectors.sourceRoot`: optional
- `runtimeVerification.strictProbeKey`: optional unless runtime pinning is enabled

### prerequisites[]

- `key`: required
- `required`: required
- `secret`: required
- `provisioning`: required (`user_input` | `discoverable`)
- `discoverySource`: required when `provisioning=discoverable` (`datasource` | `runtime_context`)
- `default`: optional for non-secrets only

### steps[]

- `order`: required, unique, strictly sequential `1..N`
- `id`: required, stable
- `targetRef`: required, valid index into `targets[]`
- `protocol`: required
- `transport`: required; must contain key that exactly matches `protocol`
- `extract`: optional
- `when`: optional deterministic condition (`all`/`any`/`not` + predicate ops)

`when` constraints:

- predicate `left` must use `context.*` or prior `step[n].*`
- predicate `op` must be one of `equals`, `not_equals`, `in`, `exists`
- `right` is required for `equals`, `not_equals`, and `in`
- forward/self step references are invalid and fail closed

### steps[].expect[]

- every step must include one or more deterministic expectations
- required fields per expectation:
  - `id`
  - `actualPath`
  - `operator`
  - `expected` (required for all operators except `field_exists`)
- supported operators:
  - `field_equals`
  - `field_exists`
  - `field_matches_regex`
  - `numeric_gte`
  - `numeric_lte`
  - `contains`
  - `probe_line_hit`
  - `outcome_status`

## plan.md (required)

Required sections:

1. `Purpose`
2. `Targets`
3. `Prerequisites`
4. `Steps`
5. `Expected Outcomes`

Allowed action verbs in `Steps`:

- `Executes`
- `Captures`
- `Uses`
- `Sets`
- `WaitsFor`
- `Verifies`

Allowed outcome verbs:

- `Returns`
- `Emits`
- `Produces`
- `Matches`
- `Passes`
