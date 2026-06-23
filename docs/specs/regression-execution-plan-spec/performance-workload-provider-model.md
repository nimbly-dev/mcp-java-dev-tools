# Performance Workload Provider Model

This document defines the workload-provider layer for performance-suite execution.

The performance plan remains the canonical contract. A workload provider only answers:

- who generates the workload
- how that workload is executed

It does not replace:

- strict line-hit proof
- threshold evaluation
- MSTA evidence

## Current Supported Providers

### `builtin`

The MCP-native workload executor already present in the performance suite.

Responsibilities:

- execute the configured workload directly
- collect request-level metrics
- coordinate strict line verification
- coordinate optional profiler/MSTA capture

### `jmeter`

The external Apache JMeter CLI workload executor.

Current supported mode:

- `generated_http`

Current scope:

- HTTP entrypoints only
- generated `.jmx` from the canonical performance plan
- non-GUI execution only
- exported `.jmx` remains importable in local Apache JMeter

JMeter remains the workload engine only. The suite still owns threshold evaluation, strict verification, and persisted run results.

## Contract Shape

When omitted, provider defaults to `builtin`.

```json
{
  "workloadProvider": {
    "type": "builtin"
  }
}
```

JMeter example:

```json
{
  "workloadProvider": {
    "type": "jmeter",
    "mode": "generated_http",
    "options": {
      "installationPath": "C:/tools/apache-jmeter-5.6.3",
      "emitJmx": true,
      "emitJtl": true,
      "emitLog": true
    }
  }
}
```

## Separation Of Concerns

- `entrypoints[]`: what target is invoked
- `loadModel`: how much load is generated
- `workloadProvider`: what engine generates the load
- `observationTargets`: deterministic execution proof targets
- `analysis.executionTiming` / `analysis.msta`: timing-analysis evidence

## Persisted Artifacts

When the JMeter provider is used, the run directory may contain supplementary workload artifacts:

- `workload.jmeter.jmx`
- `workload.jmeter.jtl`
- `workload.jmeter.log`

These are supporting artifacts. Canonical suite verdicts still come from:

- `execution.result.json`
- `evidence.json`

When a performance execution profile is exported in `ps1` or `sh` mode and the selected plan uses `workloadProvider.type=jmeter`, the export package may also contain:

- `artifacts/jmeter/<plan>.workload.jmeter.jmx`

That exported `.jmx` is an interoperability artifact for local Apache JMeter import/open, while the wrapper scripts remain replay executors.

## Compatibility Rules

- MSTA remains transport-neutral.
- The performance suite remains transport-extensible.
- `workloadProvider.type=jmeter` is HTTP-only in the current scope.

## Non-Goals In Current Scope

- custom `.jmx` as the canonical plan source
- reverse import of existing `.jmx` into MCP performance plan form
- JMeter UI as authoritative execution mode
- non-HTTP JMeter samplers
- JMeter-native pass/fail replacing performance-suite thresholds
