---
name: mcp-java-dev-tools-issue-report
description: "Create sanitized, reproducible bug/issue reports from session evidence. Use for issue tickets, failure summaries, repro steps, exceptions, or failing endpoints."
---

# MCP Java Dev Tools Issue Report

Create a reproducible issue report from the active session. Prefer current-thread evidence over speculation.

## Contract

1. Use this skill for issue reporting only.
2. Treat the current session as the primary source of truth.
3. Gather only the minimum details needed for a developer to reproduce and investigate the issue.
4. Never emit raw secrets, enterprise identifiers, or proprietary class/package names in the final output.
5. If a fact is missing, say it is missing. Do not invent repro details.

## Workflow

1. Read the user request and identify the reporting target:
   - failing method
   - failing endpoint
   - failing workflow
   - runtime/probe failure
2. Mine the current session and workspace for evidence:
   - user-provided symptoms
   - HTTP requests and responses
   - stack traces and logs
   - probe results and captures
   - relevant source locations only when needed to explain the issue
3. Normalize the issue into developer-useful facts:
   - observed behavior
   - expected behavior
   - reproducible trigger
   - affected component
   - impact
4. Sanitize the output:
   - redact secrets
   - anonymize identifiers
   - rewrite package names to neutral namespaces
   - preserve technical role and structure
5. Perform a final leak review before answering.

## Evidence Rules

1. Prefer direct runtime evidence over static guesses.
2. Prefer exact trigger requests over abstract descriptions.
3. Use short excerpts or summaries instead of long raw dumps.
4. When probe evidence exists, use it to support the issue but do not make MCP internals the center of the report.
5. If the issue was not fully reproduced, state that clearly and separate:
   - confirmed facts
   - likely cause
   - missing evidence

## Sanitization Rules

Read [references/sanitization-rules.md](references/sanitization-rules.md) before generating the final report.

Apply the rules in that file to:

1. class names
2. package names
3. organization names
4. service names
5. hostnames and URLs
6. credentials and auth material
7. business-sensitive terminology

## Output Format

Read [references/output-template.md](references/output-template.md) and follow it exactly.

Required properties of the final report:

1. Short and reproducible.
2. Safe to share with developers without leaking enterprise details.
3. Preserve technical meaning after anonymization.
4. Keep naming consistent across the whole report by using a stable alias map.

## Reporting Rules

1. `Issue Title` must be a single concise sentence.
2. `Issue Description` must summarize the failure, trigger, and effect.
3. Supporting sections must contain only evidence that materially helps reproduction or investigation.
4. Include exact request shape when known:
   - method
   - sanitized URL/path
   - sanitized query params
   - sanitized headers
   - sanitized body
5. Include expected behavior only when it can be inferred from the session or user statement.
6. Include impact only when it is grounded in evidence.

## Anonymization Policy

Preserve architectural meaning. Remove proprietary identity.

Good:

1. `CompanyClassA` -> `ClassA`
2. `SynonymsRuleController` -> `RuleController`
3. `AcmeCatalogService` -> `CatalogService`
4. `com.company.catalog.web.controller` -> `com.example.catalog.web.controller`

Bad:

1. `SynonymsRuleController` -> `<REDACTED>`
2. `CatalogService` -> `Thing`
3. removing all class/package information

## Final Leak Check

Before answering, verify the report does not contain:

1. bearer tokens
2. cookies
3. passwords
4. API keys
5. internal hostnames
6. company-specific package roots
7. proprietary class or service names when a generic alias can preserve meaning

If leakage remains, rewrite before answering.
