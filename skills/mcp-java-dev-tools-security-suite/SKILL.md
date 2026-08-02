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

## Black-box execution behavior

Black-box execution selects only locally versioned knowledge packs, generates one finite case for each declared attack profile, and invokes HTTP cases through `transport_execute` with `wrappedOnly=true`. It persists the matrix, coverage, findings, and redacted HTTP evidence under the canonical Security run Artifact path.

Missing symbolic credentials, target-boundary violations, transport failures, unexpected baseline responses, and incomplete matrix coverage are deterministic blocked outcomes.
