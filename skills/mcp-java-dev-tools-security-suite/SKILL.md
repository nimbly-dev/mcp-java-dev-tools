---
name: mcp-java-dev-tools-security-suite
description: "Execute a bounded Security Suite plan through the existing execution orchestration path, with explicit Black-box and Sidecar-assisted mode routing and fail-closed coverage semantics."
---

# MCP JVM Security Suite

This Skill Workflow is the execution router for Security Suite plans.

## Intent router

1. Load the selected execution profile and require `suiteType=security`.
2. Load and validate the Security plan Artifact through `artifact_management` with `artifactType=security_plan`.
3. Route `securityMode=blackbox` to Black-box execution or `securityMode=sidecar_assisted` to Sidecar-assisted execution.
4. Execute only the finite matrix authored by the plan; do not crawl unknown API surface.
5. Treat any required blocked case or incomplete matrix as a non-clean result.

## Outcome and proof semantics

Each case is `passed`, `confirmed`, `not_applicable`, or `blocked`. Finding proof is separately classified as `external`, `internal`, or `corroborated_external`.

Black-box execution must not use source, JAR/classpath, FQCN, Sidecar, Probe, or Strict Line Key information. Sidecar-assisted execution may use those inputs, but must not convert internal analysis into an external-exploitability claim.

## Foundation behavior

The mode-neutral foundation exposes the route and contract boundaries. Until the follow-on mode implementations are present, execution must fail closed with a deterministic foundation-only result rather than report a clean pass.
