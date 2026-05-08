# Project Artifact Validation Rules (v1)

Use these checks during `validate` to keep `SKILL.md` concise and deterministic.

## Core

1. `workspaces[]` must exist and contain at least one entry.
2. Each workspace must define `projectRoot`.
3. `runtimeContexts[]` must exist and contain at least one entry.
4. Runtime `mode` must be `terminal` or `docker`.
5. `templates/projects.terminal.example.json` is the canonical shape reference.
6. Any field not represented by the canonical shape is misaligned and must be removed before validation.

## Terminal Runtime Rules

1. If `mode=terminal` and `autoStart=true`, `startups[]` is required.
2. Each `startups[]` entry must include `name` and `command`.
3. `startups[].appdir` should resolve under `projectRoot` (fail closed when unresolved).
4. Fail closed when terminal startup obviously depends on Docker commands unless `mode=docker` is selected for that context.
5. `runtimeContexts[].startup` and `startup.workdir` are legacy and must not remain after normalization.

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

## Output Contract

Return compact results:

1. `status`: `ok | blocked`
2. `reasonCode`: stable deterministic code
3. `checks[]`: brief machine-usable checks
4. `nextAction`: single concise action
