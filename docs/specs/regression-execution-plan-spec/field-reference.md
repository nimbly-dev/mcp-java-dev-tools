# Field Reference

## Package layout

```text
.mcpjvm/
  regression/
    <regression_name>/
      metadata.json
      contract.json
      plan.md
      runs/
        <run_id>/
          context.resolved.json
          execution.result.json
          evidence.json
          correlation.json
  correlation-index.json
```

## `metadata.json`

Required fields:

- `specVersion` (string): spec compatibility marker
- `execution.intent` (string): execution intent. Current value: `regression`
- `execution.probeVerification` (boolean): whether runtime probe verification is required
- `execution.pinStrictProbeKey` (boolean): whether strict probe key must be explicitly pinned
- `execution.discoveryPolicy` (string): prerequisite discovery policy (`disabled` or `allow_discoverable_prerequisites`)

Notes:

- `probeVerification=false`: no runtime probe verification
- `probeVerification=true` and `pinStrictProbeKey=false`: strict probe key is auto-resolved
- `probeVerification=true` and `pinStrictProbeKey=true`: strict probe key must be provided by plan contract

## `contract.json`

### `targets[]`

- `type` (string): `class_method`, `class_scope`, or `module_scope`
- `selectors.fqcn` (string): primary deterministic selector
- `selectors.method` (string, optional): required for method-targeted execution
- `selectors.signature` (string, optional): required only for overload disambiguation
- `selectors.sourceRoot` (string, optional): source-root disambiguation in multi-module workspaces
- `runtimeVerification.strictProbeKey` (string, optional): explicit `FQCN#method:line` pin used only when `pinStrictProbeKey=true`

### `prerequisites[]`

- `key` (string): context key required by one or more steps
- `required` (boolean)
- `secret` (boolean)
- `provisioning` (string): `user_input` or `discoverable`
- `discoverySource` (string, required for `provisioning=discoverable`): `datasource` or `runtime_context`
- `default` (string/number/boolean/object, optional): default value used only when runtime input is absent; do not use for secrets

Deterministic resolution status values:

- `provided`
- `default_applied`
- `discoverable_pending`
- `needs_user_input`

Execution merge precedence:

1. user-provided context
2. discovered context
3. non-secret default

Discovery failure reason codes:

- `discovery_empty_result`
- `discovery_ambiguous_result`
- `discovery_adapter_failure`
- `discovery_source_unsupported`
- `discovery_timeout`
- `discovery_mutation_blocked`

Discovery governance rules:

- discovery adapters MUST operate in read-only mode
- non-read access MUST fail closed with `discovery_mutation_blocked`
- discovery runtime failures MUST be sanitized to deterministic reason codes

### `steps[]`

- `order` (number): strict execution order (`1..N`)
- `id` (string): stable step identifier
- `targetRef` (number): zero-based index into `targets[]`
- `protocol` (string): protocol classification (`http`, `grpc`, `kafka`, `custom`, etc.)
- `transport` (object, required): protocol-specific execution details under `transport.<protocol>`
- `extract` (array, optional): extraction mapping from output into run context
- `when` (object, optional): deterministic condition gate for step execution

Validation rule:

- `protocol` must map to exactly one key in `transport` with the same value (for example `protocol=http` requires `transport.http`).
- mismatched or missing transport key fails closed.
- invalid `when` shape/operator/path fails closed.

`extract` semantics:

- `extract[].from`: output path to read from current step result
- `extract[].as`: context key to store for subsequent steps
- Canonical structured extraction path: `response.bodyJson.*`
- `response.bodyJson` is the parsed JSON object when the response body is valid JSON.
- `response.body` is raw response text.
- `response.body` MAY be used for plain-text assertions or text matching, but it is not the canonical path for JSON-field extraction.
- Contracts, examples, exporters, and orchestrator flows SHOULD use `response.bodyJson.*` for JSON field extraction to avoid ambiguity between text and structured content.

`when` semantics:

- `all`: all child conditions must be true
- `any`: at least one child condition must be true
- `not`: negates one child condition
- predicate fields:
  - `left`: `context.*` or `step[n].*`
  - `op`: `equals` | `not_equals` | `in` | `exists`
  - `right`: required for `equals`/`not_equals`/`in`
- `step[n]` references must be prior steps only (`n < current order`)
- false condition result skips step (`skipped_condition_false`)
- invalid condition result fails closed with deterministic reason code

### `steps[].expect[]`

- `id` (string): stable assertion id within the step
- `actualPath` (string): deterministic dot-path read from the normalized step evaluation envelope, not directly from the persisted `execution.result.json` step row
- `operator` (string): generic assertion operator
- `expected` (any, optional): required for all operators except `field_exists`
- `required` (boolean, optional): defaults to `true`
- `message` (string, optional): human guidance context

Supported operators:

- `field_equals`
- `field_exists`
- `field_matches_regex`
- `numeric_gte`
- `numeric_lte`
- `contains`
- `probe_line_hit`
- `outcome_status`

Normalized step evaluation envelope for HTTP steps:

- `status`: step outcome used by `outcome_status`
- `response.statusCode`: HTTP status code
- `response.body`: raw response body text
- `response.bodyJson`: parsed response JSON when body parsing succeeds
- `response.headers`: response headers when available
- `transport.durationMs`: transport duration
- `transport.reasonCode`: transport failure reason when present
- `probe.hit`: strict probe verification hit flag when probe verification is enabled
- `probe.key`: strict probe key used for verification
- `probe.probeId`: probe id when provided
- `probe.coverage`: runtime verification coverage classification

Compatibility aliases accepted by the resolver:

- `statusCode` -> `response.statusCode`
- `outcome` -> `status`
- `transport.status_code` -> `response.statusCode`
- `runtime.probe.hit` -> `probe.hit`

Operator-specific guidance:

- `field_exists`: use any valid normalized path or compatibility alias
- `probe_line_hit`: use `probe.hit` as the canonical `actualPath`; `runtime.probe.hit` remains accepted for compatibility
- `outcome_status`: use `status` as the canonical `actualPath`; `outcome` remains accepted for compatibility

### `correlation` (optional)

Cross-service/cross-plan deterministic post-analysis policy.

- `enabled` (boolean): enables correlation analysis after execution.
- `crossPlan` (boolean, optional): when `true`, correlation scans compatible persisted run artifacts.
- `correlationSessionId` (string, required when `crossPlan=true`): explicit shared correlation session.
- `key.type` (string): `traceId` | `requestId` | `messageId`
- `key.value` (string, optional): explicit key value.
- `key.source.type` (string, optional): `header` | `json_path` | `capture_field`
- `key.source.path` (string, required when `key.source` is set): extraction path.
- `key.source.path` for `json_path` SHOULD use canonical normalized response paths such as `response.bodyJson.id`.
- `window.maxWindowMs` (number, required): bounded matching window.
- `window.startEpochMs` (number, optional)
- `window.endEpochMs` (number, optional)
- `probeIds[]` (string[]): target probe IDs.
- `expectedFlow[]` (string[], optional): expected service order for validation.
- `matchPolicy.requireExactKeyMatch` (boolean)
- `matchPolicy.requireWindowMatch` (boolean)
- `matchPolicy.ambiguityStrategy` (string): `fail_closed`
- `evidencePolicy` (object, optional): evidence shaping toggles.

Fail-closed preflight validations:

- `correlation_session_missing`
- `correlation_window_invalid`
- `correlation_key_invalid`

## `plan.md`

Human-readable deterministic plan.

Expected sections:

- `Purpose`
- `Targets`
- `Prerequisites`
- `Steps`
- `Expected Outcomes`

Expected style:

- numbered steps
- allowed action verbs only
- concise, deterministic statements

## `.mcpjvm/regression/<plan>/runs/<run_id>/context.resolved.json`

Resolved run-time context for a specific run.

Examples:

- extracted IDs (`postId`)
- resolved non-secret keys (`tenantId`)
- metadata timestamps
- redaction metadata (`redaction.resolvedSecretKeysOmitted`) proving that secret prerequisites were resolved but intentionally omitted from the Artifact

## `.mcpjvm/regression/<plan>/runs/<run_id>/execution.result.json`

Canonical run result for a specific run.

Expected fields:

- `status`
- `startedAt`, `endedAt`
- `preflight` block
- per-step result list
- failure reason when applicable

## `.mcpjvm/regression/<plan>/runs/<run_id>/evidence.json`

Supporting evidence for result interpretation.

Examples:

- resolved target selectors
- probe verification details
- diagnostics summary
- correlation key extraction provenance (`correlationPolicy.keySourceType`, `correlationPolicy.keySourcePath`, `correlationPolicy.keyExtractionReasonCode`)

## `.mcpjvm/regression/<plan>/runs/<run_id>/correlation.json`

Per-run persisted distributed correlation result.

Expected fields:

- `status` (`ok` | `fail_closed`)
- `reasonCode`
- `reasonMeta` (optional): compact typed diagnostics for fail-closed outcomes such as unresolved correlation key source path
- `correlationSessionId` (for cross-plan scenarios)
- `keyType`, `keyValue`
- `window`
- `expectedFlow` (optional)
- `timeline[]` (deterministically ordered matched events)
- `evidenceRefs[]` (optional)

Fail-closed correlation diagnostics:

- `reasonCode=correlation_key_extraction_failed`: configured `key.source` could not be resolved from available step evidence.
- when `reasonCode=correlation_key_extraction_failed`, `reasonMeta.sourcePath` SHOULD contain the unresolved configured path.

## `.mcpjvm/correlation-index.json`

Workspace-level index for bounded cross-plan lookup.

Expected fields:

- `version`
- `generatedAt`
- `entries[]` with:
  - `planName`, `runId`, `runPath`
  - `generatedAtEpochMs`
  - `status`, `reasonCode`
  - `keyType`, optional `keyValue`
  - optional `correlationSessionId`
  - `window`
  - `probeIds[]`

## Performance-Suite Addendum

Performance plans currently live under:

```text
.mcpjvm/<project>/plans/performance/<plan>/
```

### `metadata.json`

Required fields:

- `specVersion` (string): spec compatibility marker
- `suiteType` (string): current value `performance`
- `execution.intent` (string): current value `performance`

### `contract.json`

Required top-level sections:

- `entrypoints[]`
- `observationTargets.requiredLineHits[]`
- `loadModel`
- `successCriteria`

#### `analysis.executionTiming` (optional unless MSTA enabled)

- `enabled` (boolean)
- `provider` (string): current supported value `async-profiler`
- `provider=async-profiler` does not currently support native Windows JVM profiling; Probe `action=profiler` `start` fails closed with reason code `profiler_unsupported_platform` when unsupported.
- `event` (string, optional): provider-specific sampling event such as `cpu` or `wall`
- `intervalNanos` (number, optional)
- `outputFormat` (string): current supported value `jfr`

#### `analysis.msta` (optional)

- `enabled` (boolean)
- `mode` (string, optional):
  - `method_targets`
  - `target_plus_path`
- `methodTargets[]` (required when enabled)
  - `methodRef` (string): `fully.qualified.Class#method`
- `includePackages[]` (string[], optional): future-facing package inclusion hint
- `allowThirdPartyFrames` (boolean, optional): future-facing frame inclusion hint

Notes:

- `observationTargets.requiredLineHits[]` remains the deterministic proof surface.
- `analysis.msta.methodTargets[]` adds timing-analysis focus and does not replace required strict verification.

### Run Artifacts

Expected canonical run files:

- `context.resolved.json`
- `execution.result.json`
- `evidence.json`

Optional timing-analysis run file:

- `execution-timing.msta.json`

Persisted `msta.status` values in `execution.result.json` and `evidence.json`:

- `not_configured`
- `disabled`
- `available`
- `jfr_missing`
- `jfr_parse_failed`
- `no_anchor_samples`

Notes:

- `not_configured` means `analysis.msta` is absent in `contract.json`.
- `disabled` means `analysis.msta.enabled=false` is explicit in `contract.json`.
- `execution-timing.msta.json` remains optional and is reserved for materialized timing-analysis output or fail-closed timing-analysis results.

See `performance-msta-evidence-model.md` for the normative MSTA evidence contract and fail-closed statuses.

