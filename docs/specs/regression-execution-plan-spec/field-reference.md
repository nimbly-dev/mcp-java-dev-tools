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

## Project-Owned Long-Running Defaults (`.mcpjvm/<project>/projects.json`)

Required workspace defaults for resumable long-running orchestration:

- `workspaces[].defaults.orchestrator.resumePollMax` (positive integer)
- `workspaces[].defaults.orchestrator.resumePollIntervalMs` (positive integer)
- `workspaces[].defaults.orchestrator.resumePollTimeoutMs` (positive integer, must be `>= resumePollIntervalMs`)

Example:

```json
{
  "workspaces": [
    {
      "projectRoot": "C:\\repo\\social-platform",
      "defaults": {
        "requestTimeoutMs": 10000,
        "retryMax": 1,
        "orchestrator": {
          "resumePollMax": 30,
          "resumePollIntervalMs": 10000,
          "resumePollTimeoutMs": 300000
        }
      }
    }
  ]
}
```

Rules:

- Keep these defaults project-owned; do not author plan-level resume/poll fields.
- Keep watcher wait policy separate from orchestrator resume/poll defaults.

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
- `extract[].required` (boolean, optional): when `true`, unresolved extraction fails closed with `reasonCode=extract_path_missing`
- Canonical structured extraction path: `response.bodyJson.*`
- `response.bodyJson` is the parsed JSON object when the response body is valid JSON.
- `response.body` is raw response text.
- `response.body` MAY be used for plain-text assertions or text matching, but it is not the canonical path for JSON-field extraction.
- Contracts, examples, exporters, and orchestrator flows SHOULD use `response.bodyJson.*` for JSON field extraction to avoid ambiguity between text and structured content.
- step results SHOULD record extract outcomes for each mapping with `from`, `as`, `required`, `status`, and `reasonCode`.

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

### `watchers[]` (optional)

Bounded, fail-closed downstream completion verification policy for long-running cross-service work.
Watchers complement `correlation`:

- `correlation` proves cross-service event relationship
- `watchers` prove downstream completion or expected external-state convergence

- `id` (string): stable watcher identifier; must be unique within `contract.watchers[]`
- `dependency.stepOrder` (number): prior `steps[].order` that the watcher depends on
- `provider.type` (string): generic watcher provider classification (`probe`, `http`, `sql`, `custom`, etc.)
- `provider.transport` (object, optional): generic transport payload for provider execution
- `provider.config` (object, optional): generic provider configuration payload
- `expect[]` (array, required): watcher assertion block; reuses the same field shape and operators as `steps[].expect[]`
- `waitPolicy.timeoutMs` (number, optional): positive integer bounded wait override
- `waitPolicy.retryMax` (number, optional): positive integer bounded retry/attempt override

Validation rules:

- `provider.type` plus at least one of `provider.transport` or `provider.config` is required
- `provider.transport` and `provider.config`, when present, must be objects
- `dependency.stepOrder` must reference an existing prior step order from `steps[].order`
- `watchers[].expect[]` reuses the same validation rules as `steps[].expect[]`
- `waitPolicy`, when present, may contain only `timeoutMs` and/or `retryMax`
- `waitPolicy.timeoutMs` and `waitPolicy.retryMax` must be positive integers

Wait inheritance semantics:

- when `waitPolicy.timeoutMs` is absent, runtime should inherit the bounded timeout from resolved project/runtime default `runtime.requestTimeoutMs`
- when `waitPolicy.retryMax` is absent, runtime should inherit the bounded retry window from resolved project/runtime default `runtime.retryMax`
- watcher-level overrides remain authoritative when explicitly set
- if runtime execution cannot establish a bounded wait window from watcher overrides or inherited defaults, execution must fail closed
- watcher waits may span resumed orchestration passes; resumed execution continues the current in-progress plan rather than rerunning already completed plans

Fail-closed watcher contract validations:

- `watcher_id_invalid`
- `watcher_dependency_invalid`
- `watcher_provider_invalid`
- `watcher_wait_policy_invalid`
- `watcher_expectations_missing`
- `watcher_expectation_invalid`

### `externalVerification[]` (optional)

Deterministic downstream data-validity verification against external third-party systems after the trigger path completes.

- `id` (string): stable external verification identifier; must be unique within `contract.externalVerification[]`
- `provider.type` (string): provider discriminator. Current supported contract shapes:
  - `http`
  - `sql`
- `request` (object, required): explicit provider-discriminated execution block
  - `request.http` is required only when `provider.type=http`
  - `request.sql` is required only when `provider.type=sql`
  - provider/request mismatches fail closed
- `extract[]` (array, optional): extraction mapping from normalized external verification result into downstream context
- `expect[]` (array, required): assertion block using the same field shape and operators as `steps[].expect[]`

Validation rules:

- `provider.type` must be one of `http` or `sql`
- `request` must contain exactly one provider block and that block must match `provider.type`
- `externalVerification[].expect[]` reuses the same expectation operator contract as `steps[].expect[]`
- `externalVerification[].extract[]`, when present, must use deterministic `from` / `as` mappings and boolean `required`
- placeholder syntax follows transport rules: canonical `${key}`, compatible `{{key}}`, compatible `{{{key}}}`
- placeholder keys resolve directly from the context map; do not use `context.*` inside `${...}` or `valueFromContext`
- secret-bearing connection details and inline SQL credentials are not allowed in the public verification contract
- external verification waits may span resumed orchestration passes; resumed execution continues the current in-progress plan rather than rerunning already completed plans

#### `externalVerification[].request.http`

- `method` (string): `GET` | `POST` | `PUT` | `PATCH` | `DELETE` | `HEAD` | `OPTIONS`
- exactly one of:
  - `pathTemplate` (string): relative path resolved against runtime-owned base URL context
  - `url` (string): explicit absolute target
- `headers` (object, optional): string-valued request headers
- `body` (any, optional): provider request payload
- `timeoutMs` (number or `null`, optional): bounded override for provider execution timeout

Canonical normalized result roots for `expect[].actualPath`:

- `status`
- `response.statusCode`
- `response.body`
- `response.bodyJson`
- `response.headers`
- `response.durationMs`

#### `externalVerification[].request.sql`

- `connectionRef` (string): logical runtime/project-owned connection reference
- `statement` (string): SQL statement text
- `parameters[]` (array, optional):
  - `name` (string): statement parameter name
  - exactly one of:
    - `value` (any): literal non-secret value
    - `valueFromContext` (string): canonical context key
- `timeoutMs` (number or `null`, optional): bounded override for provider execution timeout

SQL contract boundary rules:

- `connectionRef` is required; concrete vendor/driver connection attributes live in runtime/project-owned configuration, not `contract.json`
- vendor-specific fields such as host, database, schema, catalog, instance, service name, DSN, or JDBC URL remain behind the resolved runtime/project connection config
- current runtime-owned connection resolution uses canonical context keys shaped as `sql.connection.<connectionRef>.*`
- current first concrete engine resolution is:
  - `sql.connection.<connectionRef>.kind=sqlite`
  - `sql.connection.<connectionRef>.sqlite.filePath=<db path>`
- these keys MAY be projected from project-owned env-backed `variables.contextBindings`, execution-profile `providedContext`, or explicit per-run context
- resolved `sql.connection.*` runtime keys are treated as secret context and are redacted from persisted run Artifacts

Canonical normalized result roots for `expect[].actualPath`:

- `status`
- `sql.rowCount`
- `sql.rows`
- `sql.firstRow`
- `sql.durationMs`

Fail-closed external verification contract validations:

- `external_verification_id_invalid`
- `external_verification_provider_invalid`
- `external_verification_request_invalid`
- `external_verification_extract_invalid`
- `external_verification_expectations_missing`
- `external_verification_expectation_invalid`
- `external_verification_placeholder_syntax_invalid`

### Normalized External Verification Result

This is the deterministic provider-normalized evaluation envelope for future external verification runtime execution and assertion evaluation.

- `id` (string)
- `providerType` (`http` | `sql`)
- `status` (`pass` | `fail_assertion` | `blocked_runtime`)
- `response` (object, HTTP only)
- `sql` (object, SQL only)
- `extractedContext` (object, optional)
- `extractResults[]` (optional): normalized extract outcomes with `from`, `as`, `required`, `status`, optional `value`, and optional `reasonCode`
- `assertions[]` (optional): normalized assertion outcomes with `id`, `actualPath`, `operator`, `status`, optional `expected`, optional `actual`, optional `message`, and optional `reasonCode`
- `reasonCode` / `reasonMeta` (optional): fail-closed diagnostics

Provider normalization rules:

- `providerType=http` requires `response` and must not include `sql`
- `providerType=sql` requires `sql.rowCount` plus `sql.rows[]` and must not include `response`
- `sql.firstRow` is the first row object when one exists
- `response.bodyJson` is the parsed HTTP body when parsing succeeds
- contracts SHOULD target canonical normalized paths instead of provider-native raw payload paths

### `correlation` (optional)

Cross-service/cross-plan deterministic post-analysis policy.

- `enabled` (boolean): enables correlation analysis after execution.
- `crossPlan` (boolean, optional): when `true`, correlation scans compatible persisted run artifacts.
- `correlationSessionId` (string, required when `crossPlan=true`): explicit shared correlation session.
- `key.type` (string): `traceId` | `requestId` | `messageId`
- `key.value` (string, optional): explicit key value.
- During runtime suite execution, `key.value` MAY reference a prior resolved suite correlation key via `${suite.correlation.<correlationSessionId>.keyValue}` or `${suite.correlation.last.keyValue}`.
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
- `triggerStatus` (optional): trigger/step-phase outcome before watcher aggregation (`pass` | `fail` | `blocked` | `in_progress`)
- `watcherStatus` (optional): watcher-phase aggregate outcome (`not_configured` | `pass` | `fail` | `blocked` | `in_progress`)
- `externalVerificationStatus` (optional): external-verification phase aggregate outcome (`not_configured` | `pass` | `fail` | `blocked` | `in_progress` | `skipped_dependency`)
- `startedAt`, `endedAt`
- `preflight` block
- per-step result list
- optional `watchers[]` result list
- optional `externalVerification[]` normalized result list
- failure reason when applicable

Resumed execution semantics:

- `in_progress` indicates the current run is waiting inside the active plan rather than requesting a rerun of completed plans
- when watcher or external-verification work remains active, the suite-level orchestrator resumes the same `suiteRunId` and continues the persisted active phase

### `execution.result.json.watchers[]` (optional)

Persisted watcher execution result for bounded downstream completion verification.

- `id` (string)
- `dependencyStepOrder` (number)
- `providerType` (string)
- `status` (`pass` | `fail_assertion` | `blocked_dependency` | `blocked_runtime`)
- `outcome` (`verified` | `failed_expectation` | `timed_out` | `blocked`)
- `attemptCount` (number)
- `durationMs` (number)
- `waitPolicy.timeoutMs` / `waitPolicy.retryMax` (when resolved)
- `waitPolicy.timeoutSource` / `waitPolicy.retrySource`
- `waitPolicy.pollIntervalMs` (derived runtime polling interval when resolved)
- `lastObservation` (optional): compact final observation summary used for watcher assertions/debugging without persisting raw body or header values
- `assertions[]` (optional): final watcher assertion evaluation snapshot
- `attempts[]` (optional): compact polling attempt timeline

Watcher runtime canonical reason codes:

- `watcher_verified`
- `watcher_timeout`
- `watcher_target_unreachable`
- `watcher_expectation_failed`
- `watcher_configuration_invalid`
- `watcher_dependency_invalid`

Implementation-specific failure causes such as unresolved wait policy, unsupported provider, response normalization failure,
or persistent missing assertion path should be carried in `reasonMeta.cause` rather than introducing additional top-level
watcher reason codes.

### `execution.result.json.externalVerification[]` (optional)

Persisted normalized external verification result for downstream data-validity checks.

- `id` (string)
- `providerType` (`http` | `sql`)
- `status` (`pass` | `fail_assertion` | `blocked_runtime`)
- `response` (object, HTTP only, compact persisted summary)
- `sql` (object, SQL only)
- `extractResults[]` (optional)
- `assertions[]` (optional)
- `reasonCode` / `reasonMeta` (optional)

Persisted HTTP response summary fields:

- `response.statusCode`
- `response.durationMs`
- `response.bodyFormat` (`text` | `json`)
- `response.bodyBytes`
- `response.hasBodyJson`
- `response.headerNames` (optional)

Raw response bodies and raw header values are not persisted.
Resolved extract values, assertion `actual` values, and `extractedContext` values are also not persisted.

HTTP runtime canonical reason codes:

- `external_verification_request_invalid`
- `external_verification_request_unresolved`
- `external_verification_target_unreachable`
- `external_verification_response_invalid`
- `external_verification_expectation_failed`
- `extract_path_missing`

SQL runtime canonical reason codes:

- `external_verification_connection_unresolved`
- `external_verification_connection_invalid`
- `external_verification_request_unresolved`
- `external_verification_execution_failed`
- `external_verification_response_invalid`
- `external_verification_expectation_failed`
- `extract_path_missing`

## `.mcpjvm/regression/<plan>/runs/<run_id>/evidence.json`

Supporting evidence for result interpretation.

Examples:

- resolved target selectors
- probe verification details
- diagnostics summary
- watcher execution evidence (`watcherExecutions[]`)
- external verification execution evidence (`externalVerificationExecutions[]`)
- `watcherExecutions[]` minimum shape:
  - `id`
  - `dependencyStepOrder`
  - `providerType`
  - `status` (`ok` | `fail_closed` | `timed_out`)
  - `outcome` (`verified` | `timeout` | `target_unreachable` | `expectation_failed` | `configuration_invalid` | `dependency_invalid`)
  - `attemptCount`
  - `durationMs`
  - `reasonCode`
  - `waitPolicy`
- `externalVerificationExecutions[]` minimum shape:
  - same compact persisted shape as `execution.result.json.externalVerification[]`
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

