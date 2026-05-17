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
4. Add/update external systems and health checks.
5. Resolve env key references (never env values).

## Rules

1. If project name is missing, ask the user first and do not create files yet.
2. `probe-config.json` remains authoritative for probes and baseUrl routing.
3. `projects.json` MUST NOT duplicate probe endpoint config.
4. Persist only env key names (for example `AUTH_BEARER_TOKEN`), never resolved token values.
5. Runtime context `mode` is restricted to `terminal` and `docker`.
6. Runtime context supports `autoStart` and `autoStopOnFinish` booleans (default true).
7. For `mode=terminal`, provide `startups[]` entries per app/service with `command` (+ optional `args[]`, `appdir`, `env`) when auto-start is desired.
8. External system checks may use only deterministic `tcp` or `http` checks in v1.
9. Fail closed on ambiguous discovery; do not guess ports, hosts, or auth keys.
10. `defaults.retryMax` and `defaults.requestTimeoutMs` are used by orchestrator preflight health checks.
11. `sessionExport` uses flat defaults (`includeRuntimeStartup`, `includeHealthcheckGate`) for run-session export behavior.

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
        "bearerTokenEnv": "AUTH_BEARER_TOKEN"
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
        "includeHealthcheckGate": true
      }
    }
  ]
}
```

## Workflow

1. Resolve workspace root.
2. Ask for project name when missing.
3. Build artifact path `.mcpjvm/<project-name>/projects.json`.
4. If file exists: read + normalize legacy/misaligned fields + validate + patch requested changes.
5. If file does not exist: create minimal valid structure and apply requested changes.
6. Validate end-to-end and return deterministic summary.

## Legacy/Misaligned Field Fix Rules

1. Always run normalization before validation and write.
2. Treat `templates/projects.terminal.example.json` as the canonical schema allowlist.
3. Any field not present in the canonical template is misaligned and must be removed during normalization.
4. For HTTP health checks, normalize to canonical `url` when `type=http`.
5. If normalization cannot be done deterministically, fail closed with compact output and do not write partial state.

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
