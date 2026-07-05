# Data Fields Reference (0.1.0)

This document is the simple dictionary of JSON fields emitted to the orchestrator via MCP tool outputs.

Column meanings:
- `fieldName`: JSON path emitted in `structuredContent`.
- `fieldDesc`: What the field means.
- `toolUsedBy`: Tool that emits the field.
- `required`: `true` when always present for that tool output shape, otherwise `false`.
- `exampleValue`: Representative value.

Deterministic contract policy:
- Orchestration decisions must use deterministic fields (`resultType`, `status`, `reasonCode`, `nextActionCode`, `failedStep`).
- Confidence/heuristic scores are not part of the public MCP output contract.

Text vs structured content policy (probe tools):
- `structuredContent` is the canonical machine-readable payload and remains the source of truth.
- `content[0].text` is intentionally compact for context efficiency and may omit large diagnostic bodies.
- Probe payloads are compact-by-default (metadata first); heavy capture internals are intentionally omitted.
- `executionPaths` are omitted by default. Set `MCP_PROBE_INCLUDE_EXECUTION_PATHS=true` to include them.

Capture timestamp naming:
- Capture timestamp fields use `capturedAtEpoch`.
- Execution timing fields use `executionStartedAtEpoch`, `executionEndedAtEpoch`, and `executionDurationMs`.
- Allocation delta fields use `threadAllocatedBytesDelta` (bytes) and are optional when unsupported.

## Global Failure Diagnostics Contract

Fail-closed/report outputs use this shared diagnostics shape:

```json
{
  "reasonCode": "line_unresolvable",
  "nextActionCode": "select_resolvable_line",
  "reasonMeta": {
    "failedStep": "line_validation",
    "fqcn": "com.example.Catalog"
  }
}
```

Rules:
- `reasonCode` is the stable cause-oriented routing key.
- `nextActionCode` is the stable verb-style action key.
- `reasonMeta` is optional typed context; unknown keys are ignored.
- Routing must not depend on `reasonMeta`.

examples:

`route_synthesis` with `action=create_recipe`:
```json
{
  "resultType": "report",
  "status": "execution_input_required",
  "reasonCode": "line_target_required_for_probe_mode",
  "nextActionCode": "provide_line_hint",
  "reasonMeta": {
    "failedStep": "intent_routing"
  }
}
```

`route_synthesis` with `action=infer_target`:
```json
{
  "resultType": "report",
  "status": "runtime_unreachable",
  "reasonCode": "runtime_unreachable",
  "nextActionCode": "verify_probe_reachability",
  "reasonMeta": {
    "failedStep": "runtime_line_validation"
  }
}
```

`probe_wait_for_hit`:
```json
{
  "result": {
    "hit": false,
    "inline": false,
    "reason": "timeout_no_inline_hit",
    "actionCode": "line_not_executed_in_window",
    "nextActionCode": "verify_trigger_path",
    "reasonMeta": {
      "failedStep": "wait_poll"
    }
  }
}
```

## debug_check

| fieldName | fieldDesc | toolUsedBy | required | exampleValue |
| --- | --- | --- | --- | --- |
| `ok` | Basic service health flag. | `debug_check` | true | `true` |
| `serverTime` | Server timestamp when ping response is produced. | `debug_check` | true | `"2026-03-07T04:00:00.000Z"` |
| `version` | MCP server version. | `debug_check` | true | `"0.1.0"` |


> Canonical live Probe MCP Tool: `probe`. Action mapping for the sections below: `check`, `actuate`, `status`, `capture`, `reset`, `wait_for_hit`.
## probe action=check

| fieldName | fieldDesc | toolUsedBy | required | exampleValue |
| --- | --- | --- | --- | --- |
| `probeId` (input) | Optional named probe selector resolved from active probe registry; takes precedence over `baseUrl` when both are supplied. | `probe` | false | `"order-service"` |
| `config` | Effective diagnose call configuration. | `probe` | true | `{"baseUrl":"http://127.0.0.1:9191"}` |
| `config.authConfigured` | Whether `probe.input.http.headers` were provided and applied. | `probe` | true | `true` |
| `config.authHeaderNames` | Header names applied to probe reset/status calls (values intentionally omitted). | `probe` | true | `["Authorization"]` |
| `checks` | Aggregated endpoint checks. | `probe` | true | `{"reset":{"ok":true},"status":{"ok":true}}` |
| `checks.reset` | Reset endpoint diagnostic result. | `probe` | true | `{"ok":true,"status":200}` |
| `checks.status` | Status endpoint diagnostic result. | `probe` | true | `{"ok":true,"keyDecodingOk":true}` |
| `checks.status.keyDecodingOk` | Whether the probe status key decoding behavior is valid. | `probe` | false | `true` |
| `recommendations` | Operator follow-up hints when check fails. | `probe` | true | `[]` |

## route_synthesis action=infer_target

| fieldName | fieldDesc | toolUsedBy | required | exampleValue |
| --- | --- | --- | --- | --- |
| `resultType` | Target infer response mode (`report`, `ranked_candidates`, `class_methods`, `disambiguation`). | `route_synthesis` | true | `"ranked_candidates"` |
| `status` | Inference status code for deterministic next-step routing. | `route_synthesis` | true | `"ok"` |
| `projectRoot` | Absolute project root selected by orchestrator and used for scoped inference. | `route_synthesis` | true | `"C:\\repo\\catalog-service"` |
| `hints` | Input hints used for scoped inference (`classHint` should be exact class/FQCN). | `route_synthesis` | true | `{"projectRootAbs":"C:\\repo\\catalog-service","classHint":"com.example.CatalogService"}` |
| `hints.additionalSourceRoots` | Effective normalized additional source roots included in static inference scope. | `route_synthesis` | false | `["C:\\repo\\core-module\\src\\main\\java"]` |
| `scannedJavaFiles` | Approximate Java file scan count. | `route_synthesis` | false | `412` |
| `candidates` | Ranked target candidates for runtime probe keying. | `route_synthesis` | false | `[{"key":"com.example.Catalog#save"}]` |
| `candidates[].line` | Runtime-validated strict probe line used for candidate selection (`null` when unresolved). | `route_synthesis` | false | `133` |
| `candidates[].declarationLine` | Method declaration line for candidate metadata and strict disambiguation support. | `route_synthesis` | false | `129` |
| `candidates[].firstExecutableLine` | First runtime-probe-validated executable line (`null` when no resolvable line is found in scan window). | `route_synthesis` | false | `133` |
| `candidates[].lineSelectionStatus` | Runtime line selection outcome (`validated` or `unresolved`). | `route_synthesis` | false | `"validated"` |
| `candidates[].lineSelectionSource` | Source of validated executable line when available. | `route_synthesis` | false | `"runtime_probe_validation"` |
| `class` | Selected class block in `class_methods` mode. | `route_synthesis` | false | `{"fqcn":"com.example.CatalogController"}` |
| `methods` | Method spans for selected class in `class_methods` mode. | `route_synthesis` | false | `[{"methodName":"save","startLine":42}]` |
| `methods[].firstExecutableLine` | First runtime-probe-validated executable line for each method (`null` when unresolved). | `route_synthesis` | false | `45` |
| `methods[].lineSelectionStatus` | Runtime line selection outcome per method (`validated` or `unresolved`). | `route_synthesis` | false | `"unresolved"` |
| `methods[].lineSelectionSource` | Source of validated executable line per method when available. | `route_synthesis` | false | `"runtime_probe_validation"` |
| `nextActionCode` | Verb-style deterministic follow-up action key for fail-closed outputs. | `route_synthesis` | false | `"refine_class_hint"` |
| `nextAction` | Required follow-up action when status is non-ready. | `route_synthesis` | false | `"Refine classHint and rerun"` |
| `reasonCode` | Deterministic failure/disambiguation code for fail-closed routing. | `route_synthesis` | false | `"target_ambiguous"` |
| `reasonMeta` | Optional compact typed context for diagnostics rendering. | `route_synthesis` | false | `{"failedStep":"target_selection","classHint":"CatalogController"}` |
| `failedStep` | Stage where deterministic selection failed. | `route_synthesis` | false | `"target_selection"` |
| `status=runtime_unreachable` | Fail-closed status when runtime line validation cannot reach probe endpoint. | `route_synthesis` | false | `"runtime_unreachable"` |
| `reasonCode=additional_source_roots_invalid` | Input validation failed because one or more `additionalSourceRoots` paths are missing or non-directory. | `route_synthesis` | false | `"additional_source_roots_invalid"` |
| `reasonCode=additional_source_roots_limit_exceeded` | Input validation failed because `additionalSourceRoots` exceeded max entry count (`10`). | `route_synthesis` | false | `"additional_source_roots_limit_exceeded"` |

## route_synthesis action=create_recipe

| fieldName | fieldDesc | toolUsedBy | required | exampleValue |
| --- | --- | --- | --- | --- |
| `projectRoot` | Absolute project root selected by orchestrator and used for scoped recipe generation. | `route_synthesis` | true | `"C:\\repo\\catalog-service"` |
| `hints` | Effective input hints and actuation preferences (`classHint` must be exact FQCN). | `route_synthesis` | true | `{"classHint":"com.example.catalog.CatalogService","lineHint":88}` |
| `hints.additionalSourceRoots` | Effective normalized additional source roots included in static inference scope. | `route_synthesis` | false | `["C:\\repo\\core-module\\src\\main\\java"]` |
| `hints.mappingsBaseUrl` | Optional runtime mappings endpoint URL used for runtime-first discovery (for example Spring Actuator mappings endpoint). | `route_synthesis` | false | `"http://127.0.0.1:8080/actuator/mappings"` |
| `hints.discoveryPreference` | Request discovery routing preference (`static_only`, `runtime_first`, `runtime_only`). | `route_synthesis` | false | `"runtime_first"` |
| `hints.apiBasePath` | Optional API context/base path provided by orchestrator and applied to request candidates/trigger paths (anti-duplication). | `route_synthesis` | false | `"/api/v1"` |
| `inferredTarget` | Best inferred runtime target for probe verification. | `route_synthesis` | false | `{"key":"com.example.CatalogService#save","line":88}` |
| `requestCandidates` | HTTP request candidates inferred from code-based synthesizer analysis. | `route_synthesis` | true | `[{"method":"POST","path":"/v1/catalog"}]` |
| `executionPlan` | Step plan emitted for execution/verification tooling. Report mode emits compact action-code steps. | `route_synthesis` | true | `{"selectedMode":"single_line_probe"}` |
| `executionPlan.routingReason` | Routing reason code for selected mode (`regression_no_probe`, `single_line_probe`, etc). | `route_synthesis` | true | `"regression_no_probe"` |
| `executionPlan.steps[].actionCode` | Compact step action code in report mode (no verbose instruction strings). | `route_synthesis` | false | `"request_candidate_missing"` |
| `resultType` | Output category (`recipe` or `report`). | `route_synthesis` | true | `"recipe"` |
| `status` | Recipe generation status for orchestration decisions (`*_ready` or fail-closed report status). | `route_synthesis` | true | `"single_line_probe_ready"` |
| `reasonCode` | Deterministic synthesis/report reason code for fail-closed routing. | `route_synthesis` | false | `"spring_entrypoint_not_proven"` |
| `nextActionCode` | Verb-style deterministic follow-up action key for fail-closed/report outputs. | `route_synthesis` | false | `"select_resolvable_line"` |
| `reasonMeta` | Optional compact typed context for diagnostics rendering. | `route_synthesis` | false | `{"failedStep":"line_validation","fqcn":"com.example.Catalog"}` |
| `reasonCode=target_ambiguous` | Multiple target candidates remained plausible for current `classHint`/`methodHint`, so orchestration failed closed before request synthesis. | `route_synthesis` | false | `"target_ambiguous"` |
| `reasonCode=target_type_not_found` | Resolver could not match `classHint` to a unique target type in scope. | `route_synthesis` | false | `"target_type_not_found"` |
| `reasonCode=target_type_ambiguous` | Resolver matched multiple target types and failed closed without picking one implicitly. | `route_synthesis` | false | `"target_type_ambiguous"` |
| `reasonCode=target_method_not_found` | Resolver matched target type but not the requested method hint. | `route_synthesis` | false | `"target_method_not_found"` |
| `reasonCode=project_root_invalid` | Resolver rejected project root during AST mapping resolution. | `route_synthesis` | false | `"project_root_invalid"` |
| `reasonCode=mapper_plugin_unavailable` | Java request-mapper/plugin bootstrap failed before entrypoint proof. | `route_synthesis` | false | `"mapper_plugin_unavailable"` |
| `reasonCode=runtime_mappings_input_required` | Runtime mappings discovery was requested but `mappingsBaseUrl` was missing/invalid for `runtime_only` mode. | `route_synthesis` | false | `"runtime_mappings_input_required"` |
| `reasonCode=runtime_mappings_unreachable` | Runtime mappings endpoint could not be reached (network/non-2xx response). | `route_synthesis` | false | `"runtime_mappings_unreachable"` |
| `reasonCode=runtime_mappings_unauthorized` | Runtime mappings endpoint rejected request authorization (`401`/`403`). | `route_synthesis` | false | `"runtime_mappings_unauthorized"` |
| `reasonCode=runtime_mappings_invalid_payload` | Runtime mappings endpoint returned payload that could not be parsed into deterministic mapping candidates. | `route_synthesis` | false | `"runtime_mappings_invalid_payload"` |
| `reasonCode=runtime_mapping_not_found` | Runtime mappings endpoint had no deterministic route for current `classHint` + `methodHint`. | `route_synthesis` | false | `"runtime_mapping_not_found"` |
| `reasonCode=runtime_mapping_ambiguous` | Runtime mappings endpoint returned multiple plausible routes for current `classHint` + `methodHint`. | `route_synthesis` | false | `"runtime_mapping_ambiguous"` |
| `reasonCode=additional_source_roots_invalid` | Input validation failed because one or more `additionalSourceRoots` paths are missing or non-directory. | `route_synthesis` | false | `"additional_source_roots_invalid"` |
| `reasonCode=additional_source_roots_limit_exceeded` | Input validation failed because `additionalSourceRoots` exceeded max entry count (`10`). | `route_synthesis` | false | `"additional_source_roots_limit_exceeded"` |
| `nextAction` (target candidate missing) | For `reasonCode=target_candidate_missing`, guidance is refined when class inventory proves an exact class match with zero method bodies (for example inherited implementation in another module root). | `route_synthesis` | false | `"Matched class has no method bodies in projectRootAbs. If methods are inherited, use parent module/source roots."` |
| `failedStep` | Specific synthesis stage that failed proof. | `route_synthesis` | false | `"spring_entrypoint_resolution"` |
| `reasonCode` (execution input gating) | When `status=execution_input_required`, reason maps to the first unresolved category (`auth_input_required`, `request_confirmation_required`, `actuation_input_required`, `line_target_required_for_probe_mode`, `request_candidate_missing`). | `route_synthesis` | false | `"request_confirmation_required"` |
| `failedStep` (execution input gating) | Stage marker paired with execution-input reason (`auth_resolution`, `request_confirmation`, `actuation_resolution`, `intent_routing`, `request_synthesis`). | `route_synthesis` | false | `"request_confirmation"` |
| `selectedMode` | Final routed internal execution mode mapped from user-facing `intentMode` (`line_probe` or `regression`). | `route_synthesis` | true | `"single_line_probe"` |
| `executionReadiness` | Execution gate (`ready` or `needs_user_input`). | `route_synthesis` | true | `"ready"` |
| `missingInputs` | Missing runtime/auth inputs blocking execution. | `route_synthesis` | true | `[]` |
| `synthesizerUsed` | Internal synthesizer plugin selected for request synthesis. | `route_synthesis` | false | `"spring"` |
| `applicationType` | Framework type derived from selected synthesizer (not runtime introspection). | `route_synthesis` | false | `"spring"` |
| `attemptedStrategies` | Ordered synthesis strategies attempted by the selected plugin. | `route_synthesis` | true | `["spring_annotation_mapping","spring_call_chain_resolution"]` |
| `evidence` | Compact evidence lines used for deterministic synthesis and pushback context. | `route_synthesis` | true | `["request_source=spring_mvc"]` |
| `evidence[] (mapping_source=bytecode_annotation)` | Indicates Spring request mapping was proven from compiled class annotations fallback (for example `target/classes`) when source mapping was insufficient. | `route_synthesis` | false | `"mapping_source=bytecode_annotation"` |
| `evidence[] (mapping_source=runtime_actuator)` | Indicates request mapping was proven from runtime actuator mappings endpoint (`mappingsBaseUrl`) before static synthesis fallback. | `route_synthesis` | false | `"mapping_source=runtime_actuator"` |
| `trigger` | Protocol-aware trigger envelope emitted by synthesis. | `route_synthesis` | false | `{"kind":"http","method":"POST","path":"/v1/catalog"}` |
| `auth` | Auth inference result and next-step hints. | `route_synthesis` | true | `{"status":"ok","strategy":"bearer"}` |
| `notes` | Run notes and routing/inference diagnostics. Report mode is compact/failure-focused. | `route_synthesis` | true | `["execution_readiness=ready"]` |
| `notes[] (context_path_hint=...)` | Optional non-blocking prompt note when `apiBasePath` is not provided but request synthesis succeeds. | `route_synthesis` | false | `"context_path_hint=Optional apiBasePath (for example /api/v1) can be supplied..."` |
| `runtimeCapture` | Optional runtime capture preview from live probe status. | `route_synthesis` | false | `{"status":"available","capturePreview":{"captureId":"abc123"}}` |
| `runtimeCapture.lineValidation` | Optional line-validation hint from runtime capture enrich pass. | `route_synthesis` | false | `"invalid_line_target"` |
| `runtimeCapture.lineResolvable` | Optional line-resolvable hint from runtime capture enrich pass. | `route_synthesis` | false | `false` |
| `rendered` | Optional rendered template output when `outputTemplate` is supplied. | `route_synthesis` | false | `"Reproduction execution plan..."` |

## probe action=actuate

| fieldName | fieldDesc | toolUsedBy | required | exampleValue |
| --- | --- | --- | --- | --- |
| `request` | Actuation request envelope sent to probe endpoint. | `probe` | true | `{"url":"http://127.0.0.1:9191/__probe/actuate"}` |
| `request.body.action` | Session actuation action (`arm` or `disarm`). | `probe` | true | `"arm"` |
| `request.body.sessionId` | Required actuation session identifier. | `probe` | true | `"regression-run-42"` |
| `request.body.targetKey` | Required strict line key for `action=arm`. | `probe` | false | `"com.example.Catalog#save:88"` |
| `request.body.returnBoolean` | Required branch decision for `action=arm`. | `probe` | false | `true` |
| `request.body.ttlMs` | Required session TTL for `action=arm`. | `probe` | false | `15000` |
| `response` | Raw endpoint response payload. | `probe` | true | `{"status":200,"json":{"action":"arm","scopeState":"armed"}}` |
| `response.json.scopeState` | Session scope state (`armed`, `expired`, `disarmed`). | `probe` | false | `"armed"` |
| `response.json.expiresAtEpoch` | Expiry timestamp for armed sessions. | `probe` | false | `1773318672847` |

## probe action=status

| fieldName | fieldDesc | toolUsedBy | required | exampleValue |
| --- | --- | --- | --- | --- |
| `probeId` (input) | Optional named probe selector resolved from active probe registry; takes precedence over `baseUrl` when both are supplied. | `probe` | false | `"order-service"` |
| `request` | Status request details (canonical `key`, URL, timeout; `resolvedKey` appears only when canonicalization differs). | `probe` | true | `{"key":"com.example.Catalog#save:88"}` |
| `response` | Compact normalized status payload (`status` + essential `json` fields). | `probe` | true | `{"status":200,"json":{"hitCount":1}}` |
| `response.json.contractVersion` | Probe contract marker. | `probe` | false | `"0.1.0"` |
| `response.json.hitCount` | Probe hit counter for the line key. | `probe` | false | `1` |
| `response.json.lastHitEpoch` | Last hit Unix-epoch timestamp in JVM host wall-clock milliseconds. | `probe` | false | `1739671200000` |
| `response.json.lineValidation` | Line validation verdict (`resolvable` or `invalid_line_target`). | `probe` | false | `"resolvable"` |
| `response.json.capturePreview` | Compact runtime preview metadata from Java agent (`available`, `captureId`, timestamp, optional path list). | `probe` | false | `{"available":true,"captureId":"abc123"}` |
| `response.json.capturePreview.capturedAtEpoch` | Capture preview Unix-epoch timestamp in JVM host wall-clock milliseconds. | `probe` | false | `1739671200456` |
| `response.json.capturePreview.executionStartedAtEpoch` | Invocation start Unix-epoch timestamp in JVM host wall-clock milliseconds. | `probe` | false | `1739671200401` |
| `response.json.capturePreview.executionEndedAtEpoch` | Invocation end Unix-epoch timestamp in JVM host wall-clock milliseconds. | `probe` | false | `1739671200456` |
| `response.json.capturePreview.executionDurationMs` | Invocation elapsed wall-clock duration in milliseconds (`executionEndedAtEpoch - executionStartedAtEpoch`, non-negative). | `probe` | false | `55` |
| `response.json.capturePreview.threadAllocatedBytesDelta` | Per-invocation thread allocation delta in bytes (`exit - enter`, non-negative). Omitted when runtime support is unavailable. | `probe` | false | `4096` |
| `response.json.capturePreview.executionPaths` | Optional execution-path frames captured at runtime when `MCP_PROBE_INCLUDE_EXECUTION_PATHS=true`. | `probe` | false | `["CatalogController.listCatalogShoes()#42"]` |
| `response.json.runtime` | Runtime observe/session-actuation payload. | `probe` | false | `{"mode":"observe","activeSessionCount":0}` |
| `response.json.runtime.sessionId` | Representative active session id when actuation is armed. | `probe` | false | `"regression-run-42"` |
| `response.json.runtime.scopeState` | Runtime scope state snapshot (`armed` or `disarmed`). | `probe` | false | `"disarmed"` |
| `response.json.runtime.activeSessionCount` | Number of active actuation sessions after TTL pruning. | `probe` | false | `1` |
| `response.json.runtime.appPort.value` | Runtime application port hint when inferable (`null` when unknown). | `probe` | false | `8082` |
| `response.json.runtime.appPort.source` | Source used to infer app port hint. | `probe` | false | `"system_property:server.port"` |
| `result` | Guidance block when runtime alignment fails. | `probe` | false | `{"reason":"invalid_line_target","actionCode":"runtime_not_aligned"}` |
| `mode` | Batch marker when `keys[]` is used. | `probe` | false | `"probe_batch"` |
| `operation` | Batch operation identifier. | `probe` | false | `"status"` |
| `summary` | Batch success/failure summary. | `probe` | false | `{"total":2,"ok":1,"failed":1}` |
| `results` | Batch per-key rows with probe outcome details. | `probe` | false | `[{"key":"...:88","apiOutcome":"ok"}]` |

## probe action=capture

| fieldName | fieldDesc | toolUsedBy | required | exampleValue |
| --- | --- | --- | --- | --- |
| `probeId` (input) | Optional named probe selector resolved from active probe registry; takes precedence over `baseUrl` when both are supplied. | `probe` | false | `"order-service"` |
| `request` | Capture fetch request details. | `probe` | true | `{"captureId":"abc123","url":"http://127.0.0.1:9191/__probe/capture?captureId=abc123"}` |
| `response` | Compact capture fetch response metadata (`status` only). | `probe` | true | `{"status":200}` |
| `result.found` | Whether capture payload exists and was returned. | `probe` | true | `true` |
| `result.capture` | Compact capture metadata (`captureId`, `methodKey`, timestamp, args/return/thrown presence flags). | `probe` | false | `{"captureId":"abc123","argsCount":1,"hasReturnValue":true}` |
| `result.capture.capturedAtEpoch` | Capture Unix-epoch timestamp in JVM host wall-clock milliseconds. | `probe` | false | `1739671200456` |
| `result.capture.executionStartedAtEpoch` | Invocation start Unix-epoch timestamp in JVM host wall-clock milliseconds. | `probe` | false | `1739671200401` |
| `result.capture.executionEndedAtEpoch` | Invocation end Unix-epoch timestamp in JVM host wall-clock milliseconds. | `probe` | false | `1739671200456` |
| `result.capture.executionDurationMs` | Invocation elapsed wall-clock duration in milliseconds (`executionEndedAtEpoch - executionStartedAtEpoch`, non-negative). | `probe` | false | `55` |
| `result.capture.threadAllocatedBytesDelta` | Per-invocation thread allocation delta in bytes (`exit - enter`, non-negative). Omitted when runtime support is unavailable. | `probe` | false | `4096` |
| `result.capture.executionPaths` | Optional execution-path frames when `MCP_PROBE_INCLUDE_EXECUTION_PATHS=true`. | `probe` | false | `["CatalogService.save()#88"]` |
| `result.reason` | Error reason when capture is unavailable. | `probe` | false | `"capture_not_found"` |

## probe action=reset

| fieldName | fieldDesc | toolUsedBy | required | exampleValue |
| --- | --- | --- | --- | --- |
| `probeId` (input) | Optional named probe selector resolved from active probe registry; takes precedence over `baseUrl` when both are supplied. | `probe` | false | `"order-service"` |
| `request` | Reset selector request details (canonical `key`; optional `resolvedKey` only when transformed from input). | `probe` | true | `{"key":"com.example.Catalog#save:88"}` |
| `response` | Compact reset response metadata (`status`, plus selector/reason metadata in batch mode). | `probe` | true | `{"status":200}` |
| `result` | Guidance block when line target is invalid. | `probe` | false | `{"reason":"invalid_line_target"}` |
| `mode` | Batch marker for multi-key/class reset. | `probe` | false | `"probe_batch"` |
| `operation` | Batch operation identifier. | `probe` | false | `"reset"` |
| `summary` | Batch outcome summary. | `probe` | false | `{"total":3,"ok":2,"failed":1}` |
| `results` | Batch per-key reset rows. | `probe` | false | `[{"key":"...:88","apiOutcome":"ok"}]` |

## probe action=wait_for_hit

| fieldName | fieldDesc | toolUsedBy | required | exampleValue |
| --- | --- | --- | --- | --- |
| `probeId` (input) | Optional named probe selector resolved from active probe registry; takes precedence over `baseUrl` when both are supplied. | `probe` | false | `"order-service"` |
| `request` | Polling request and retry configuration (`key` canonical; optional `resolvedKey` only when transformed from input). | `probe` | true | `{"key":"com.example.Catalog#save:88","maxRetries":1}` |
| `request.waitStartEpoch` | Unix-epoch millisecond timestamp when current wait attempt started. | `probe` | false | `1773318672847` |
| `request.triggerWindowStartEpoch` | Reset-aware Unix-epoch start used for strict inline classification. | `probe` | false | `1773318658526` |
| `request.triggerLeadMs` | Milliseconds between wait start and trigger window start (`waitStartEpoch - triggerWindowStartEpoch`). | `probe` | false | `14321` |
| `baseline` | Baseline probe snapshot used for inline hit diffing. | `probe` | false | `{"hitCount":0,"lastHitEpoch":0}` |
| `result.hit` | Whether a hit was detected in current wait window. | `probe` | true | `true` |
| `result.inline` | Whether detected hit is inline to current execution window. | `probe` | true | `true` |
| `result.reason` | Failure reason when no inline hit is confirmed. | `probe` | false | `"timeout_no_inline_hit"` |
| `result.actionCode` | Action code for deterministic orchestrator next-step routing. | `probe` | false | `"line_not_executed_in_window"` |
| `result.nextActionCode` | Verb-style deterministic follow-up action key for wait failure outputs. | `probe` | false | `"verify_trigger_path"` |
| `result.nextAction` | Human-readable follow-up action. | `probe` | false | `"verify_trigger_path_or_branch_then_rerun_probe_wait_for_hit"` |
| `result.reasonMeta` | Optional compact typed context for diagnostics rendering. | `probe` | false | `{"failedStep":"wait_poll","waitedMs":4000}` |
| `result.lastStatus` | Last observed probe status payload. | `probe` | false | `{"hitCount":0}` |

## execution_profile_export

| fieldName | fieldDesc | toolUsedBy | required | exampleValue |
| --- | --- | --- | --- | --- |
| `resultType` | Output shape discriminator for execution-profile export. | `execution_profile_export` | true | `"execution_profile_export"` |
| `status` | Export status (`ok`) or fail-closed report status. | `execution_profile_export` | true | `"ok"` |
| `mode` | Selected export mode. | `execution_profile_export` | true | `"sh"` |
| `exportId` | Deterministic export label resolved from selector input. | `execution_profile_export` | true | `"20260520-153152-regression-test-run"` |
| `executionProfile` | Execution profile selector echoed when supplied by the caller. | `execution_profile_export` | false | `"regression-test-run"` |
| `exportDirAbs` | Fresh one-off export directory containing generated artifacts. | `execution_profile_export` | true | `"C:\\repo\\.mcpjvm\\test-project\\exports\\2026-05-21-..."` |
| `output.scriptPathAbs` | Absolute path to the generated executable replay script. | `execution_profile_export` | true | `"C:\\repo\\.mcpjvm\\test-project\\exports\\...\\run-execution-profile.sh"` |
| `output.readmePathAbs` | Absolute path to generated export usage notes when the selected mode emits a README artifact. SH mode does not emit this file. | `execution_profile_export` | false | `"C:\\repo\\.mcpjvm\\test-project\\exports\\...\\README.ps1.md"` |
| `output.collectionPathAbs` | Absolute path to generated Postman collection artifact when `mode=postman`. | `execution_profile_export` | false | `"C:\\repo\\.mcpjvm\\test-project\\exports\\...\\collection.postman.json"` |
| `output.environmentPathAbs` | Absolute path to generated Postman environment artifact when `mode=postman`. | `execution_profile_export` | false | `"C:\\repo\\.mcpjvm\\test-project\\exports\\...\\environment.postman.json"` |
| `reasonCode` | Deterministic failure code for fail-closed report outputs. Includes Postman-specific gates such as `postman_script_conversion_required`, `postman_script_invalid_format`, and `postman_provisioning_not_supported`. | `execution_profile_export` | false | `"postman_provisioning_not_supported"` |
| `nextActionCode` | Verb-style deterministic follow-up action key for fail-closed outputs. | `execution_profile_export` | false | `"choose_supported_mode"` |
| `reasonMeta` | Optional compact typed context for diagnostics rendering. | `execution_profile_export` | false | `{"mode":"postman","supportedModes":["ps1","sh","postman"]}` |

Postman mode operator recovery guidance:
- `postman_script_conversion_required`: replace referenced non-JS script with `.js/.mjs/.cjs`, or use `ps1/sh` mode.
- `postman_script_invalid_format`: fix JS syntax or Postman-script structure for the referenced script.
- `postman_script_non_convertible`: ensure the script exists in workspace `scripts` with a resolvable file path.
- `postman_provisioning_not_supported`: move provisioning/startup/teardown outside Postman export; provision environment first.
- `postman_export_blocked` with `reasonMeta.cause=unsupported_transport`: keep Postman profiles HTTP-only.
- `postman_export_blocked` with `reasonMeta.cause=url_unresolved`: provide explicit `url` or resolvable `pathTemplate` + base URL context.
- `postman_export_blocked` with `reasonMeta.cause=url_unrunnable`: ensure final URL is absolute (`http(s)://...`) or Postman base-variable rooted (`{{var}}/...`), not path-only.

Postman variable normalization policy:
- Regression-plan Artifact authoring remains canonical `${var}`.
- Execution resolution accepts compatibility aliases `{{var}}` and `{{{var}}}` for externally-authored/imported plans and normalizes them before prerequisite resolution.
- Exporter normalizes `${var}` placeholders to `{{var}}` for URL, headers, and body fields.
- Exporter emits referenced Postman variables into `environment.values` deterministically so Runner can resolve them.
- Exporter applies contract prerequisite string defaults into `environment.values` when keys are referenced.
- When `includeResolvedSecrets=true`, exporter resolves `auth.bearer` from workspace env via `variables.bearerTokenEnv` when available.
- Exporter supports `contextBindings` (prerequisite key -> env key) and uses it with highest precedence for value resolution.
- Exporter supports `contextValues` (direct key -> value) with highest precedence for run-scoped export context.
- Referenced required prerequisites that remain unresolved fail closed (`postman_export_blocked` with `reasonMeta.cause=required_prerequisite_unresolved`).

## artifact_management

| fieldName | fieldDesc | toolUsedBy | required | exampleValue |
| --- | --- | --- | --- | --- |
| `resultType` | Output discriminator for unified Artifact lifecycle operations. | `artifact_management` | true | `"artifact"` |
| `status` | Deterministic operation status (`ok`) or fail-closed reason status. | `artifact_management` | true | `"ok"` |
| `artifactType` | Artifact class selected by caller (`probe_config`, `project_context`, `regression_plan`, `run_result`, `execution_export`). | `artifact_management` | true | `"project_context"` |
| `action` | Requested lifecycle action (`read`, `validate`, `upsert`, `list`, `generate`, `reload`). | `artifact_management` | true | `"validate"` |
| `input` | Typed per-artifact payload object. The top-level request is generic; artifact-specific fields are nested under `input`. | `artifact_management` | true | `{"projectName":"catalog-service","query":{"select":["summary"]}}` |
| `input.projectName` | Canonical project Artifact identity for `project_context`, `regression_plan`, `run_result`, and `execution_export` operations. Required for orchestrator-grade calls in multi-project workspaces. | `artifact_management` | false | `"post-service"` |
| `input.projectRootAbs` | Optional deterministic project-root selector for `project_context` validation and scope cross-checking. | `artifact_management` | false | `"C:\\repo\\social-platform\\post-service\\post-app"` |
| `input.query.select` | Optional projection selectors for structured reads to reduce payload size. Supported examples: `project_context` (`summary`, `executionProfiles`, `runtimeContexts`, `scripts`, `runPrerequisites`, `workspaces`), `regression_plan` (`summary`, `targets`, `prerequisites`, `steps`, `metadata`, `contract`, `plan`), `run_result` (`executionResult`, `evidence`). | `artifact_management` | false | `["summary","executionProfiles"]` |
| `input.query.prerequisites.offset` | Required zero-based window start for `regression_plan` `prerequisites` section reads when `select` includes `prerequisites`. | `artifact_management` | false | `0` |
| `input.query.prerequisites.limit` | Required window size for `regression_plan` `prerequisites` section reads when `select` includes `prerequisites`. | `artifact_management` | false | `50` |
| `input.query.steps.offset` | Required zero-based window start for `regression_plan` `steps` section reads when `select` includes `steps`. | `artifact_management` | false | `0` |
| `input.query.steps.limit` | Required window size for `regression_plan` `steps` section reads when `select` includes `steps`. | `artifact_management` | false | `25` |
| `reasonCode` | Deterministic blocked reason code (for example `artifact_action_not_allowed`, `project_artifact_missing`). | `artifact_management` | false | `"artifact_action_not_allowed"` |
| `nextActionCode` | Verb-style deterministic follow-up action key for blocked outputs. | `artifact_management` | false | `"artifact_action_not_allowed"` |
| `reasonMeta` | Optional typed diagnostics including allowed action presets for the selected `artifactType`. | `artifact_management` | false | `{"allowedActions":["read","validate","upsert","reload"]}` |
| `artifact` | Artifact payload returned by read actions (shape varies by `artifactType`). | `artifact_management` | false | `{"workspaces":[{"projectRoot":"C:\\repo"}]}` |
| `configFileAbs` | For `probe_config` actions, absolute path to the resolved `.mcpjvm/probe-config.json` Artifact. | `artifact_management` | false | `"C:\\repo\\.mcpjvm\\probe-config.json"` |
| `activeProfile` | For `probe_config` actions, active resolved profile name. | `artifact_management` | false | `"dev"` |
| `profileSource` | For `probe_config` actions, profile resolution source (`env`, `workspace`, `default`). | `artifact_management` | false | `"workspace"` |
| `implicitProbeId` | For `probe_config` actions, the implicitly selected probe id when the active profile contains exactly one Probe. | `artifact_management` | false | `"order-service"` |
| `probeCount` | For `probe_config` actions, count of registered probes in the active profile. | `artifact_management` | false | `3` |
| `allowNonWrappedExecutable` | For `probe_config` actions, whether runtime execution may bypass wrapper enforcement. | `artifact_management` | false | `false` |
| `lastReloadAt` | For `probe_config` `read`/`reload`, ISO timestamp of the most recent reload attempt. | `artifact_management` | false | `"2026-05-01T14:20:55.000Z"` |
| `lastReloadStatus` | For `probe_config` `read`/`reload`, last reload outcome (`ok` or `error`). | `artifact_management` | false | `"ok"` |
| `lastReloadError` | For `probe_config` `read`/`reload`, last reload error message when `lastReloadStatus=error`. | `artifact_management` | false | `"Unexpected token..."` |
| `probes` | For `probe_config` actions, registered probe descriptors (`id`, `baseUrl`, selectors, runtime metadata). | `artifact_management` | false | `[{"id":"order-service","baseUrl":"http://127.0.0.1:9190"}]` |
| `projectRootAbs` | For `project_context` `validate`, the normalized project root validated against the selected project scope. | `artifact_management` | false | `"C:\\repo\\social-platform\\post-service\\post-app"` |
| `buildMarkers` | For `project_context` `validate`, build markers found directly under `projectRootAbs`. | `artifact_management` | false | `["pom.xml"]` |
| `hasBuildMarker` | For `project_context` `validate`, whether any Maven/Gradle marker exists in the project root. | `artifact_management` | false | `true` |
| `javaSourceRoots` | For `project_context` `validate`, basic Java source roots discovered under `projectRootAbs`. | `artifact_management` | false | `["C:\\repo\\social-platform\\post-service\\post-app\\src\\main\\java"]` |
| `hasJavaSourceRoot` | For `project_context` `validate`, whether at least one basic Java source root exists. | `artifact_management` | false | `true` |
| `summary.stepCount` | For default `regression_plan` reads or explicit `summary` selection, the number of plan steps in `contract.json`. | `artifact_management` | false | `115` |
| `summary.prerequisiteCount` | For default `regression_plan` reads or explicit `summary` selection, the number of prerequisites in `contract.json`. | `artifact_management` | false | `265` |
| `prerequisites.offset` | Returned zero-based start offset for a windowed `regression_plan` prerequisites section. | `artifact_management` | false | `0` |
| `prerequisites.limit` | Requested window size for a windowed `regression_plan` prerequisites section. | `artifact_management` | false | `50` |
| `prerequisites.returned` | Number of prerequisite entries actually returned in the current window. | `artifact_management` | false | `50` |
| `prerequisites.total` | Total prerequisite count for the plan Artifact. | `artifact_management` | false | `265` |
| `prerequisites.items` | Current window slice of regression plan prerequisites. | `artifact_management` | false | `[{"key":"ctx-1"}]` |
| `steps.offset` | Returned zero-based start offset for a windowed `regression_plan` steps section. | `artifact_management` | false | `0` |
| `steps.limit` | Requested window size for a windowed `regression_plan` steps section. | `artifact_management` | false | `25` |
| `steps.returned` | Number of step entries actually returned in the current window. | `artifact_management` | false | `25` |
| `steps.total` | Total step count for the plan Artifact. | `artifact_management` | false | `115` |
| `steps.items` | Current window slice of regression plan steps. | `artifact_management` | false | `[{"id":"step-1"}]` |

`artifact_management` action presets:
- `probe_config`: `read`, `validate`, `upsert`, `reload`
- `project_context`: `read`, `validate`, `upsert`, `list`
- `regression_plan`: `read`, `validate`, `upsert`, `list`
- `run_result`: `read`, `list`
- `execution_export`: `read`, `list`, `generate`

Typed request envelope examples:
- `{"artifactType":"probe_config","action":"validate","input":{}}`
- `{"artifactType":"probe_config","action":"reload","input":{}}`
- `{"artifactType":"project_context","action":"read","input":{"projectName":"catalog","query":{"select":["summary","executionProfiles"],"executionProfile":"smoke"}}}`
- `{"artifactType":"project_context","action":"validate","input":{"projectName":"post-service","projectRootAbs":"C:\\repo\\social-platform\\post-service\\post-app"}}`

## execution_orchestration

| fieldName | fieldDesc | toolUsedBy | required | exampleValue |
| --- | --- | --- | --- | --- |
| `resultType` | Output discriminator for runtime-suite orchestration. | `execution_orchestration` | true | `"execution_orchestration"` |
| `status` | Suite status for synchronous execution (`pass`, `fail`, `blocked`, `partial_fail`) or resumable progress checkpoint (`in_progress`). | `execution_orchestration` | true | `"in_progress"` |
| `action` | Requested orchestration lifecycle action. | `execution_orchestration` | true | `"execute"` |
| `projectName` | Explicit project selector for multi-project-safe suite execution. | `execution_orchestration` | true | `"test-project-performance"` |
| `executionProfile` | Workspace execution profile selected from `projects.json` for either a `regression` or `performance` suite. | `execution_orchestration` | true | `"test-performance-stress-suite"` |
| `suiteRunId` | Canonical suite-level run id reused across resumable calls. Present on `in_progress` and terminal outputs. | `execution_orchestration` | false | `"06-10-2026-12-07-41AM"` |
| `statusArtifactPath` | Persisted suite-status artifact path for resumable progress and terminal summaries. | `execution_orchestration` | false | `.mcpjvm/test-project-performance/suite-runs/06-10-2026-12-07-41AM/execution_orchestration.result.json` |
| `executionPolicy` | Effective suite execution policy. | `execution_orchestration` | false | `"stop_on_fail"` |
| `planRuns` | Ordered cumulative per-plan execution summary for completed progress so far or the final suite result. | `execution_orchestration` | false | `[{"order":1,"planName":"mcp-tool-performance-replay-spec","status":"executed","runStatus":"pass","runId":"06-10-2026-12-07-42AM"}]` |
| `nextPlanOrder` | Next plan order to execute when `status="in_progress"`. | `execution_orchestration` | false | `2` |
| `completedPlanCount` | Number of cumulative plans already persisted for the suite run. | `execution_orchestration` | false | `1` |
| `correlations` | Optional suite-level cross-plan correlation summary for completed runs. | `execution_orchestration` | false | `[{"correlationSessionId":"order-flow","status":"ok","reasonCode":"ok","keyType":"traceId","contributingPlans":["producer-plan","consumer-plan"]}]` |

HTTP transport diagnostics notes:
- `http_payload_invalid` may include `reasonMeta.missingFields` with one or more missing required transport fields.
- `http_payload_invalid` may include `reasonMeta.cause` for deterministic URL-assembly failures such as `url_missing`, `api_base_url_missing_for_path_template`, `api_base_url_missing_for_path`, `absolute_path_template_not_promoted`, or `absolute_path_not_promoted`.
- When present, `reasonMeta.pathTemplate` or `reasonMeta.path` identifies the authored relative or absolute request target involved in URL synthesis failure.
- Regression-plan authoring remains canonical on `apiBaseUrl`; legacy initial prerequisite `baseUrl` is compatibility-only and must not be treated as a new canonical context key.
- Shared execution-profile script failures surface deterministic `reasonCode="script_execution_failed"`; required service or probe readiness failures remain `external_healthcheck_failed`.

## Skill-Orchestrated Route Pushback (`mcp-java-dev-tools-line-probe-run`, `mcp-java-dev-tools-regression-suite`)

These fields are emitted by orchestration summaries in skill-guided runs when probe route resolution cannot be proven uniquely.

| fieldName | fieldDesc | toolUsedBy | required | exampleValue |
| --- | --- | --- | --- | --- |
| `reasonCode` | Deterministic failure code (`toolchain_unavailable`, `probe_route_not_found`, `probe_route_ambiguous`). | `mcp-java-dev-tools-line-probe-run (summary), mcp-java-dev-tools-regression-suite (summary)` | true | `"probe_route_ambiguous"` |
| `attemptedCandidates` | Candidate runtime routes evaluated before pushback. | `mcp-java-dev-tools-line-probe-run (summary), mcp-java-dev-tools-regression-suite (summary)` | true | `[{"apiBase":"http://localhost:8082","probeBase":"http://localhost:9192"}]` |
| `validationResults` | Per-candidate validation outcomes (probe/API/line alignment checks). | `mcp-java-dev-tools-line-probe-run (summary), mcp-java-dev-tools-regression-suite (summary)` | true | `[{"probeReachable":true,"apiReachable":false}]` |
| `nextAction` | Action required from the user to proceed after pushback. | `mcp-java-dev-tools-line-probe-run (summary), mcp-java-dev-tools-regression-suite (summary)` | true | `"Provide a unique runtime/service selector or stop conflicting services."` |
| `reproSteps` | Ordered executable reproduction steps emitted for both success and pushback outputs. | `mcp-java-dev-tools-line-probe-run (summary), mcp-java-dev-tools-regression-suite (summary)` | true | `["1. Validate projectRootAbs", "2. Call route_synthesis action=create_recipe", "3. Resolve runtime route"]` |

## Regression Conditional Execution (`mcp-java-dev-tools-regression-suite`)

| fieldName | fieldDesc | toolUsedBy | required | exampleValue |
| --- | --- | --- | --- | --- |
| `executionResult.steps[].status` | Step outcome status including conditional skip (`skipped_condition_false`). | `mcp-java-dev-tools-regression-suite` | true | `"skipped_condition_false"` |
| `executionResult.steps[].extract[]` | Per-mapping extraction outcome with deterministic diagnostics (`from`, `as`, `required`, `status`, `reasonCode`). | `mcp-java-dev-tools-regression-suite` | false | `[{"from":"response.body.id","as":"triggeredEventId","required":false,"status":"unresolved","reasonCode":"extract_path_missing"}]` |
| `executionResult.steps[].conditionEvaluation.status` | Deterministic condition evaluation result (`true`, `false`, `blocked_invalid`). | `mcp-java-dev-tools-regression-suite` | false | `false` |
| `executionResult.steps[].conditionEvaluation.reasonCode` | Deterministic reason when condition evaluation is blocked. | `mcp-java-dev-tools-regression-suite` | false | `"step_condition_forward_reference"` |
| `executionResult.triggerStatus` | Trigger/step-phase aggregate run status before watcher aggregation. | `mcp-java-dev-tools-regression-suite` | false | `"pass"` |
| `executionResult.watcherStatus` | Watcher-phase aggregate status (`not_configured`, `pass`, `fail`, `blocked`). | `mcp-java-dev-tools-regression-suite` | false | `"blocked"` |
| `executionResult.watchers[]` | Per-watcher bounded completion-verification results with deterministic outcome/reason fields and attempt counters. | `mcp-java-dev-tools-regression-suite` | false | `[{"id":"indexed_ready","status":"pass","outcome":"verified","attemptCount":3}]` |
| `evidence.watcherExecutions[]` | Persisted watcher polling evidence summary including wait-policy provenance and compact attempt timeline. | `mcp-java-dev-tools-regression-suite` | false | `[{"id":"indexed_ready","waitPolicy":{"timeoutSource":"project_default","retrySource":"project_default"},"attempts":[{"attempt":1,"status":"pass"}]}]` |





