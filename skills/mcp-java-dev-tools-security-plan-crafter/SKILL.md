---
name: mcp-java-dev-tools-security-plan-crafter
description: "Create or update deterministic Security Suite plans under `.mcpjvm/<project>/plans/security`, routing shared contract authoring to Black-box or Sidecar-assisted mode rules."
---

# MCP JVM Security Plan Crafter

This Skill Workflow authors the mode-neutral Security Artifact contract. It does not execute attack cases.

## Workflow

1. Research only declared or locally observable entrypoints and authentication profiles.
2. Select exactly one `securityMode`: `blackbox` or `sidecar_assisted`.
3. For `blackbox`, use the reviewed local catalog by default. Use `securityKnowledge.packRefs` only as an advanced targeted/reproduction override; do not perform live research during CI execution.
4. Declare the finite external boundary, HTTP entrypoints with safe `baseline` request recipes, authentication profiles, fixture capabilities, and optional bounded `customCases`/constraints. Normal cases are generated from applicable catalog rule templates; do not author `attackProfiles` in Black-box plans.
5. Apply the shared safety and verdict policies from the Security Artifact Spec.
6. Validate the contract through `artifact_management` with `artifactType=security_plan` before upserting it.

For `sidecar_assisted`, author or deterministically resolve `runtimeTargets` during craft/build. Use the existing `route_synthesis` `infer_target` or `class_methods` action for FQCN/method selection, validate the selected line against the selected Probe, then persist the resulting `Class#method:line` target in the security plan. Each target must bind one entrypoint to one configured Probe ID and one Strict Line Key. If a FQCN/method reference resolves to zero or multiple Strict Line Keys, fail closed instead of guessing. Runtime expectation lists (`mustHitRuntimeTargets` and `mustNotHitRuntimeTargets`) may only reference targets bound to the same entrypoint.

When a Sidecar target depends on application or explicitly selected dependency instrumentation, persist an `instrumentationTargets` declaration and link it from `runtimeTargets[].instrumentationTargetRef`. Use `scope=application` for application classes and `scope=dependency` with a bounded `dependencyRef` for dependency classes. Craft/build validates the selected FQCN against the workspace Probe include/exclude rules and fails closed when the selected class is not instrumentable.

## Mode boundary

- `blackbox` plans contain network-visible target data and may omit `securityKnowledge.packRefs` for catalog-default selection; they must not contain source, JAR, FQCN, Sidecar, Probe, or Strict Line Key data.
- `sidecar_assisted` plans may declare runtime targets with Probe IDs and Strict Line Keys, but an external-exploitability claim still requires an external attack path.
- Sidecar-assisted plans may inspect application or explicitly declared dependency symbols during craft/build, but must persist only bounded target references and sanitized diagnostics; never persist credentials, raw environment variables, or arbitrary source dumps.

## Required contract fields

`suiteType`, `securityMode`, `targetBoundary`, `entrypoints`, `authenticationProfiles`, `exhaustiveness`, `safetyPolicy`, and `verdictPolicy` are required. Each HTTP entrypoint should declare a safe `baseline` recipe containing only path parameters, query values, headers, and body data; missing or unusable recipes block generated cases. `customCases` is optional and is an override surface, not the normal case source. Never persist resolved credentials, tokens, passwords, or raw environment variables.

## Fail-closed rules

Block when the attack matrix is not finite, references are unresolved, the target boundary permits external network access, or the selected mode contains data forbidden by its mode boundary. Do not infer unknown entrypoints or credentials.
