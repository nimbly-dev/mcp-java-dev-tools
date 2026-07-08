# Project Artifact Validation Rules (v1)

Use these checks during `validate` to keep `SKILL.md` concise and deterministic.

## Core

1. `workspaces[]` must exist and contain at least one entry.
2. Each workspace must define `projectRoot`.
3. Each workspace must define `defaults.orchestrator.resumePollMax`, `defaults.orchestrator.resumePollIntervalMs`, and `defaults.orchestrator.resumePollTimeoutMs` as positive integers.
4. `defaults.orchestrator.resumePollTimeoutMs` must be greater than or equal to `defaults.orchestrator.resumePollIntervalMs`.
5. `runtimeContexts[]` should exist when the project artifact manages runtime startup.
6. Runtime `mode` must be `terminal` or `docker`.
7. `tools/spec/project-artifact-spec/src/project_artifact.util.ts` is the canonical validation contract.
8. `templates/projects.terminal.example.json` is an authoring starter, not a strict schema allowlist.
9. Do not remove validator-supported optional fields as non-canonical:
   1. `executionProfiles[].runtimeConfig`
   2. `executionProfiles[].plans[].onFail`
   3. `executionProfiles[].plans[].providedContext`

## Terminal Runtime Rules

1. If `mode=terminal` and `autoStart=true`, `startups[]` is required.
2. Each `startups[]` entry must include `name` and `command`.
3. `startups[].appdir` should resolve under `projectRoot` (fail closed when unresolved).
4. Fail closed when terminal startup obviously depends on Docker commands unless `mode=docker` is selected for that context.
5. `runtimeContexts[].startup` and `startup.workdir` are unsupported and must not remain after normalization.

## Shared Script Rules

1. Workspace-level `scripts[]` is the canonical location for token refresh, seed, validation, env generation, and other run preparation.
2. `runtimeContexts[].startups[]` must be reserved for application/service lifecycle startup.
3. Each `scripts[]` entry must include `name` and `command`.
4. `scripts[].phase` may be `preRuntime`, `postRuntime`, `postHealthcheck`, or `prePlan`.
5. `executionProfiles[].scriptRefs[]` may contain script names or `{ "name": "...", "phase": "..." }` objects.
6. Each `executionProfiles[].scriptRefs[].name` must match a `scripts[].name`.
7. Use `scripts[].envFileArg` when a script accepts an env-file parameter that export runners should point to export-local `project.env`.
8. Replayability guard: absolute machine paths are not allowed in `scripts[].appdir`, path-like `scripts[].args[]`, `runPrerequisites[].script.scriptPath`, or `runPrerequisites[].script.cwd`.

## Docker Runtime Rules

1. If `mode=docker`, `composeFile` is required.
2. `composeFile` should resolve under `projectRoot`.

## Probe/Runtime Alignment

1. `projects.json` must not duplicate probe route ownership from `probe-config.json`.
2. If strict probe verification is expected by regression specs, startup strategy must make probe endpoint reachable; otherwise return blocked guidance.

## Secrets and Env

1. Persist only env key names (for example `AUTH_BEARER_TOKEN`), never resolved values.
2. If an auth env key is referenced and missing from `envFile`, return fail-closed `env_key_missing`.

## Health Check Shape Rules

1. `type=tcp` uses `target`.
2. `type=http` uses `url` per canonical shape.

## Orchestrator Defaults Rules

1. Keep orchestrator resiliency configuration under `workspaces[].defaults.orchestrator`.
2. Keep orchestrator resiliency defaults project-owned; do not introduce plan-level resume/poll knobs.
3. Keep watcher wait-policy defaults distinct from orchestrator resume/poll defaults.

## Output Contract

Return compact results:

1. `status`: `ok | blocked`
2. `reasonCode`: stable deterministic code
3. `checks[]`: brief machine-usable checks
4. `nextAction`: single concise action
