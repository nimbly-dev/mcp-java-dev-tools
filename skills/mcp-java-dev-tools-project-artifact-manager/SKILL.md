---
name: mcp-java-dev-tools-project-artifact-manager
description: "Manage persistent project artifacts under .mcpjvm/<project-name>/projects.json. Use when the user wants project context setup for runtime contexts, external systems, and health checks without duplicating probe-config."
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
2. `probe-config.json` remains authoritative for probes and baseUrl routing.
3. `projects.json` MUST NOT duplicate probe endpoint config.
4. Persist only env key names (for example `AUTH_BEARER_TOKEN`), never resolved token values.
5. Runtime context `mode` is restricted to `terminal` and `docker`.
6. Runtime context supports `autoStart` and `autoStopOnFinish` booleans (default true).
7. For `mode=terminal`, provide `startups[]` entries per app/service with `command` (+ optional `args[]`, `appdir`, `env`) when auto-start is desired.
8. Runtime startup entries must start/stop application runtime only; token refresh, seed, validation, and env preparation belong in shared `scripts[]`.
9. Shared scripts are referenced by `executionProfiles[].scriptRefs[]` and may declare `phase`, `command`, `args[]`, `appdir`, `env`, and `envFileArg`.
10. External system checks may use only deterministic `tcp` or `http` checks in v1.
11. Fail closed on ambiguous discovery; do not guess ports, hosts, or auth keys.
12. `defaults.retryMax` and `defaults.requestTimeoutMs` are used by orchestrator preflight health checks.
13. `sessionExport` uses flat defaults (`includeRuntimeStartup`, `includeHealthcheckGate`, `includeResolvedSecrets`) for execution-profile export behavior. `includeResolvedSecrets=true` is a trusted-local setting and makes exported packages sensitive.

## Required Artifact Path

```
.mcpjvm/<project-name>/projects.json
```

## Required Shape

```json
{
  "workspaces": [
    {
      "projectRoot": "C:\\workspace\\example",
      "envFile": ".env",
      "variables": {
        "bearerTokenEnv": "AUTH_BEARER_TOKEN",
        "keycloakClientIdEnv": "KEYCLOAK_CLIENT_ID",
        "keycloakClientSecretEnv": "KEYCLOAK_CLIENT_SECRET",
        "keycloakUsernameEnv": "KEYCLOAK_USERNAME",
        "keycloakPasswordEnv": "KEYCLOAK_PASSWORD"
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
          "scriptRefs": [
            "keycloak-token-bootstrap"
          ],
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
        "retryMax": 1
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
5. Call `artifact_management` with `artifactType=project_context` and `action=validate` before persistence.
6. Call `artifact_management` with `artifactType=project_context` and `action=upsert` to persist.
7. Validate end-to-end and return deterministic summary.

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
2. `defaults.requestTimeoutMs`: default timeout for required external system checks when per-check timeout is not set.
3. Keep values small and deterministic for fast preflight feedback.

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
