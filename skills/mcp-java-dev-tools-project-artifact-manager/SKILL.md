---
name: mcp-java-dev-tools-project-artifact-manager
description: "Manage `.mcpjvm/<project>/projects.json` project context artifacts (read/validate/upsert/list) for runtime contexts, external systems, and health checks."
---

# MCP Java Dev Tools Project Artifact Manager

Use this skill to manage project-level artifacts while keeping probe routing in `probe-config.json`.

## Scope

1. Initialize `.mcpjvm/<project-name>/projects.json`.
2. Validate deterministic project artifact shape.
3. Add/update runtime contexts (`terminal`/`docker`).
4. Add/update shared scripts used by execution profiles.
5. Add/update external systems and health checks.
6. Resolve env key references (never env values).
7. Use `artifact_management` as the canonical MCP Tool for read/validate/upsert/list artifact lifecycle operations.

## Rules

1. If project name is missing, ask the user first and do not create files yet.
2. Treat `projectName` as the canonical Artifact identity for `artifact_management` calls.
3. Use `projectRootAbs` only as deterministic scope validation or cross-check input when needed.
4. `probe-config.json` remains authoritative for probes and baseUrl routing.
5. `projects.json` MUST NOT duplicate probe endpoint config.
6. Persist only env key names (for example `AUTH_BEARER_TOKEN`), never resolved token values.
7. Runtime context `mode` is restricted to `terminal` and `docker`.
8. Runtime context supports `autoStart` and `autoStopOnFinish` booleans (default true).
9. For `mode=terminal`, provide `startups[]` entries per app/service with `command` (+ optional `args[]`, `appdir`, `env`) when auto-start is desired.
10. Runtime startup entries must start/stop application runtime only; token refresh, seed, validation, and env preparation belong in shared `scripts[]`.
11. Shared scripts are referenced by `executionProfiles[].scriptRefs[]` and may declare `phase`, `command`, `args[]`, `appdir`, `env`, and `envFileArg`.
12. Shared scripts and run-prerequisite scripts must be replayable: use relative paths only (`scriptPath`, `appdir`, and path-like `args[]`); absolute machine paths are invalid.
13. External system checks may use only deterministic `tcp` or `http` checks in v1.
14. Fail closed on ambiguous discovery; do not guess ports, hosts, or auth keys.
15. `defaults.retryMax` and `defaults.requestTimeoutMs` are used by suite-owned runtime operations by default, including health checks, wrapped HTTP execution, and replayable bootstrap/prerequisite scripts unless a narrower timeout is set.
16. `defaults.orchestrator.resumePollMax`, `defaults.orchestrator.resumePollIntervalMs`, and `defaults.orchestrator.resumePollTimeoutMs` are required project-owned Execution Orchestrator resiliency defaults.
17. Keep watcher wait-policy defaults distinct from `defaults.orchestrator`; watcher polling and outer orchestration polling are separate concerns.
18. `sessionExport` uses flat defaults for `includeRuntimeStartup` and `includeHealthcheckGate`.
19. `sessionExport.includeResolvedSecrets` must not auto-enable secret export; resolved secrets require explicit request opt-in at export time.
20. SQLite state-store rebuild is an explicit maintenance operation through `artifact_management` with `artifactType=run_result` and `action=rebuild`; it is never a normal read or write shortcut.
21. Rebuild must scan canonical run Artifacts, validate a temporary database, and atomically replace the live store only after validation succeeds.

## Required Artifact Path

```
.mcpjvm/<project-name>/projects.json
```

## Run-state Store Foundation

Regression operational state uses a separate local SQLite store at `.mcpjvm/<project-name>/run-state.sqlite`. It is owned by the Artifact Management Feature Module, not by this Skill Workflow or by an always-on service. Keep `projectName` explicit, persist only workspace-relative Artifact paths, and fail closed on a locked, corrupt, or unsupported store. Rebuild or backfill is an explicit maintenance operation; never delete or silently recreate a failed store.

## Required Shape

```json
{
  "workspaces": [
    {
      "executionRoot": "C:\\workspace\\example",
      "envFile": ".env",
      "variables": {
        "bearerTokenEnv": "AUTH_BEARER_TOKEN",
        "keycloakClientIdEnv": "KEYCLOAK_CLIENT_ID",
        "keycloakClientSecretEnv": "KEYCLOAK_CLIENT_SECRET",
        "keycloakUsernameEnv": "KEYCLOAK_USERNAME",
        "keycloakPasswordEnv": "KEYCLOAK_PASSWORD",
        "contextBindings": {
          "apiBaseUrl": "BASE_URL",
          "tenantId": "TENANT_ID"
        }
      },
      "runtimeContexts": [
        {
          "name": "terminal-cli",
          "mode": "terminal",
          "autoStart": true,
          "autoStopOnFinish": true,
          "startups": [
            {
              "name": "customers-service",
              "command": "java",
              "args": ["-jar", "target\\customers.jar"],
              "appdir": "spring-petclinic-customers-service"
            }
          ]
        },
        {
          "name": "docker-compose",
          "mode": "docker",
          "composeFile": "docker-compose.yml"
        }
      ],
      "scripts": [
        {
          "name": "keycloak-token-bootstrap",
          "phase": "postHealthcheck",
          "command": "powershell",
          "args": [
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            ".mcpjvm\\example\\scripts\\refresh-keycloak-token.ps1"
          ],
          "appdir": ".",
          "envFileArg": "-EnvFile"
        }
      ],
      "executionProfiles": [
        {
          "executionProfile": "regression-test-run",
          "runtimeContextName": "docker-compose",
          "executionPolicy": "stop_on_fail",
          "scriptRefs": ["keycloak-token-bootstrap"],
          "plans": [
            {
              "order": 1,
              "planName": "owners-regression"
            }
          ]
        }
      ],
      "externalSystems": [
        {
          "name": "postgres",
          "kind": "database",
          "host": "localhost",
          "port": 5432,
          "healthChecks": [
            {
              "id": "tcp-open",
              "type": "tcp",
              "target": "localhost:5432",
              "required": true
            }
          ]
        }
      ],
      "defaults": {
        "requestTimeoutMs": 10000,
        "retryMax": 1,
        "orchestrator": {
          "resumePollMax": 30,
          "resumePollIntervalMs": 10000,
          "resumePollTimeoutMs": 300000
        }
      },
      "sessionExport": {
        "includeRuntimeStartup": true,
        "includeHealthcheckGate": true,
        "includeResolvedSecrets": false
      }
    }
  ]
}
```

## Workflow

1. Resolve workspace root.
2. Ask for project name when missing.
3. Call `artifact_management` with `artifactType=project_context` and `action=read|list` to load current state.
4. Normalize and prepare requested changes in-memory.
5. Call `artifact_management` with `artifactType=project_context` and `action=validate` before persistence, passing explicit `projectName` and optional `projectRootAbs` only when a scope cross-check is required.
6. Call `artifact_management` with `artifactType=project_context` and `action=upsert` to persist.
7. Validate end-to-end and return deterministic summary.
8. For state-store recovery, call `artifact_management` with `artifactType=run_result`, `action=rebuild`, and explicit `projectName`; use `strict=true` when every discovered run must be reconstructible.
9. Report bounded rebuild counts and reason rows. Never delete or clear the live SQLite store in place.

## Misaligned Field Fix Rules

1. Always run normalization before validation and write.
2. Treat the TypeScript validator contract in `tools/spec/project-artifact-spec/src/project_artifact.util.ts` as canonical.
3. Do not use `templates/projects.terminal.example.json` as a strict allowlist for field removal.
4. Optional validator-supported fields are canonical and must not be removed as non-canonical:
   1. `executionProfiles[].runtimeConfig`
   2. `executionProfiles[].plans[].onFail`
   3. `executionProfiles[].plans[].providedContext`
5. For HTTP health checks, normalize to canonical `url` when `type=http`.
6. If normalization cannot be done deterministically, fail closed with compact output and do not write partial state.

## Validate Action (Keep It Lean)

1. Run a dedicated `validate` pass before writing updates.
2. Reuse rules in `references/validation-rules.md` to avoid duplicating logic in `SKILL.md`.
3. Return compact fail-closed output:
   1. `status`
   2. `reasonCode`
   3. `checks[]`
   4. `nextAction`
4. When creating a new project artifact, prefer starting from `templates/projects.terminal.example.json`.

## Runtime Health Defaults

1. `defaults.retryMax`: retry attempts for required external system checks.
2. `defaults.requestTimeoutMs`: default timeout for suite-owned runtime operations when a narrower timeout is not set, including required external system checks, wrapped HTTP execution, and replayable bootstrap/prerequisite scripts.
3. `defaults.orchestrator.resumePollMax`: bounded outer resume/poll pass count for long-running execution orchestration.
4. `defaults.orchestrator.resumePollIntervalMs`: bounded sleep interval between outer orchestration resume passes.
5. `defaults.orchestrator.resumePollTimeoutMs`: bounded total outer orchestration wait budget.
6. Keep runtime-operation defaults and outer orchestration defaults small and deterministic for fast fail-closed feedback.
7. These defaults control resumption of the same `suiteRunId`; they do not authorize rerunning already completed plans.
8. These defaults are distinct from watcher wait policy:
   - watcher wait policy governs one downstream completion check inside one plan
   - orchestrator defaults govern bounded continuation of the whole in-progress suite across tool-call boundaries

## Long-Running Execution Example

Use this shape when execution profiles can wait inside watcher or external-verification phases:

```json
{
  "executionProfile": "watcher-sql-run",
  "runtimeContextName": "terminal-cli",
  "executionPolicy": "stop_on_fail",
  "plans": [
    {
      "order": 1,
      "planName": "event-cross-service"
    }
  ]
}
```

Required workspace defaults:

```json
{
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
```

Resumed orchestration semantics:

1. `execution_orchestration` returns `status="in_progress"` with a `suiteRunId` when the suite is still waiting inside the current plan.
2. Resume with the same `suiteRunId`.
3. Continue the persisted in-progress plan phase rather than rerunning prior completed plans.

## Shared Scripts

1. Put reusable setup scripts in workspace-level `scripts[]`, not inside `runtimeContexts[].startups[]`.
2. Use `runtimeContexts[].startups[]` only for app/service lifecycle startup.
3. Reference shared scripts from `executionProfiles[].scriptRefs[]`.
4. Use phases to make execution order explicit:
   1. `preRuntime`: before runtime startup.
   2. `postRuntime`: after runtime startup command, before health gates.
   3. `postHealthcheck`: after required health gates pass.
   4. `prePlan`: immediately before regression plan transport execution.
5. If a script updates an env file, declare `envFileArg` so export runners can pass their export-local `project.env`.
6. Export packages copy referenced shared scripts into their own `scripts/` folder and invoke the copied script, not the original workspace path.

## Implementation Status

1. `projects.json` management supports declaring shared `scripts[]` and `executionProfiles[].scriptRefs[]`.
2. Regression suite runner execution of `executionProfiles[].scriptRefs[]` by phase is not implemented yet.
3. This skill defines the canonical shape for new artifacts; it does not automatically migrate setup scripts from existing `runtimeContexts[].startups[]` entries into shared `scripts[]`.

## Extensibility

This skill supports modular external-system discovery guidance in:

1. `README.md`
2. `references/postgres.md`
3. `references/dynamodb.md`
4. `references/keycloak.md`
5. `references/validation-rules.md`
6. `templates/projects.terminal.example.json`

When adding new systems, extend `references/` with one file per system family and keep rules deterministic.

## Fail-Closed Reason Codes

1. `project_name_missing`
2. `project_artifact_missing`
3. `project_artifact_invalid`
4. `workspace_root_invalid`
5. `env_key_missing`
6. `runtime_context_unknown`
7. `external_system_invalid`
8. `external_healthcheck_failed`
9. `discovery_ambiguous`
