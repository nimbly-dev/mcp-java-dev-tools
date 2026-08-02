---
name: mcp-java-dev-tools-security-plan-crafter
description: "Create or update deterministic Security Suite plans under `.mcpjvm/<project>/plans/security`, routing shared contract authoring to Black-box or Sidecar-assisted mode rules."
---

# MCP JVM Security Plan Crafter

This Skill Workflow authors the mode-neutral Security Artifact contract. It does not execute attack cases.

## Workflow

1. Research only declared or locally observable entrypoints and authentication profiles.
2. Select exactly one `securityMode`: `blackbox` or `sidecar_assisted`.
3. Build a finite attack matrix from the declared entrypoints, authentication profiles, and attack profiles.
4. Apply the shared safety and verdict policies from the Security Artifact Spec.
5. Validate the contract through `artifact_management` with `artifactType=security_plan` before upserting it.

## Mode boundary

- `blackbox` plans contain network-visible target data and pinned `securityKnowledge.packRefs`; they must not contain source, JAR, FQCN, Sidecar, Probe, or Strict Line Key data.
- `sidecar_assisted` plans may declare runtime targets with Probe IDs and Strict Line Keys, but an external-exploitability claim still requires an external attack path.

## Required contract fields

`suiteType`, `securityMode`, `targetBoundary`, `entrypoints`, `authenticationProfiles`, `attackProfiles`, `exhaustiveness`, `safetyPolicy`, and `verdictPolicy` are required. Never persist resolved credentials, tokens, passwords, or raw environment variables.

## Fail-closed rules

Block when the attack matrix is not finite, references are unresolved, the target boundary permits external network access, or the selected mode contains data forbidden by its mode boundary. Do not infer unknown entrypoints or credentials.
