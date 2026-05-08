# Strict Probe Gate

## Authoritative Rules

1. If `metadata.execution.probeVerification=true`, probe reachability is mandatory.
2. If probe is unreachable:
   - if `runtime.autoStart=true`, attempt runtime auto-start once
   - re-check probe once
   - if still unreachable, fail closed with `external_healthcheck_failed`
3. If JVM is already running without probe wiring, do not mutate command line in place; require restart through runtime startup path.
4. `manual_wrapped_transport` fallback is allowed only when `probeVerification=false`.
5. If project runtime context exists, ad-hoc `java -jar` fallback is non-compliant:
   - do not run ad-hoc direct JVM startup
   - fail closed and require runtime restart via `projects.json` runtime context/startups

## Terminal Runtime Alignment

1. Probe bind port MUST match configured `probeBaseUrl` port.
2. Prefer `--probe-id <id>` and `--probe-config <path>`.
3. Use `--agent-port <port>` only as explicit override.
4. Do not rely on auto-scanned probe ports in strict mode.
5. Wrapper script usage is optional implementation detail; probe-wired startup is mandatory.
6. Startup source-of-truth is `projects.json` runtime context, not temporary shell heuristics.

## Non-Compliant Process Gate (Unskippable)

1. Before phase 4, inspect target JVM process command line when available.
2. If process is running but missing `-javaagent`, mark runtime as non-compliant.
3. For non-compliant runtime:
   - stop fail-closed at preflight
   - restart only through probe-wired startup
   - do not execute endpoint steps before restart verification

## Listener Verification (Required Before Execution)

1. Verify runtime API listener is reachable.
2. Verify probe listener (`/__probe/status`) is reachable on configured probe port.
3. If either listener is unreachable, stop with deterministic `external_healthcheck_failed`.

## Canonical Windows Probe-Wired Startup Example

```powershell
cmd /c "set JAVA_TOOL_OPTIONS=-javaagent:%MCP_JAVA_AGENT_JAR%=port=9194;include=org.springframework.samples.petclinic.visits..** && java -jar visits-service.jar --server.port=8082"
```

Use probe port and include pattern resolved from `.mcpjvm/probe-config.json` for the selected probeId.
