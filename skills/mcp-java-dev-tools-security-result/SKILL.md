---
name: mcp-java-dev-tools-security-result
description: "Render deterministic Security Suite coverage, findings, evidence references, outcomes, and proof classifications from persisted run Artifacts."
---

# MCP JVM Security Result

This Skill Workflow reports Security Suite results; it does not execute plans or alter findings.

## Report contract

Read the canonical Security run Artifact and report:

- run status and selected `securityMode`;
- planned versus executed finite-matrix coverage;
- resolved knowledge snapshot (selection mode, pack refs, compatibility metadata, and content digests);
- counts for `passed`, `confirmed`, `not_applicable`, and `blocked` cases;
- findings with severity, category, and proof classification;
- redacted evidence references and deterministic reason codes.

## Verdict rules

Never render incomplete required coverage as a clean pass. Preserve the distinction between external proof, internal hardening evidence, and corroborated external proof. If the canonical run Artifact is missing, invalid, or conflicting, fail closed and return a blocked result with the exact missing evidence.

The mode-neutral foundation defines the report shape. Black-box findings use external HTTP request/response evidence; Sidecar-assisted evidence interpretation remains owned by its mode implementation.

For Sidecar-assisted runs, include the fresh Probe evidence references, runtime identity, required/forbidden target interpretation, and proof classification. Distinguish `external`, `internal`, and `corroborated_external`; a Sidecar-only signal must never be rendered as an externally demonstrated exploit.

Render selected application/dependency instrumentation as bounded runtime evidence, including the target scope and dependency reference when present. Do not render raw source, credentials, environment values, or unrestricted classpath contents.
