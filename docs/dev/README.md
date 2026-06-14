# Dev Guides

This section is for developers adding framework support — whether you're extending an existing integration or bringing in a brand-new framework from scratch.

**New here? Start with adapters and plugins.** You don't need to understand probe internals to be productive. Work from the outside in, and only go deeper if something pulls you there.

---

## Start Here

- [Creating Your Own Request Mappers](./creating-your-own-request-mappers/README.md)
- [Creating Your Own Synthesizers](./creating-your-own-synthesizers/README.md)

Both guides include runtime config examples for:
- in-repo extensions
- external/private extension modules

---

## How the Architecture Fits Together

There are two extension surfaces — one on the Java side, one on the TypeScript side. Together they form the HTTP recipe pipeline:

| Surface | Language | What it does |
|---|---|---|
| **Request Mapper Adapters** | Java | Reads framework/controller source code and extracts normalized HTTP request candidates |
| **Synthesizer Plugins** | TypeScript | Takes resolved mapping context and produces an orchestrator-ready recipe |

The **probe** is always the runtime truth source. Your adapters and synthesizers should enrich and route — not override what the probe actually observed at runtime.

---

## Where Your Code Lives

| What you're building | Where it goes |
|---|---|
| Java request mapper adapter | `java-agent/mappers-adapters/adapter-request-mapper-*` |
| TS synthesizer plugin | `tools/synthesizers/tools-*` |

**Starting templates** (intentionally off by default — these are scaffolds, not production modules):
- `java-agent/mappers-adapters/adapter-request-mapper-example`
- `tools/synthesizers/tools-synthesizer-example`

---

## Which Surface Do You Need?

**Build a request mapper adapter when:**
- Your framework uses route annotations or controller conventions that aren't currently extracted
- AST extraction is missing for your framework

**Build a synthesizer plugin when:**
- Route extraction already works, but recipe generation needs framework-specific logic
- Auth, header, or body assumptions vary by framework convention

**Starting a brand-new HTTP framework?** You'll likely need both.

> **Note:** The current adapter and synthesizer extension points are HTTP-specific. If your target transport isn't HTTP, the shared request-mapping contract and recipe model aren't sufficient on their own — non-HTTP support would require widening those contracts first.

---

## Engineering Guardrails

A few principles that hold across all extension work:

- **Keep the core framework-agnostic.** Framework-specific logic belongs in adapters and plugins. Don't imply non-HTTP transport support unless the shared contracts are widened first.
- **Fail closed, always.** When proof is insufficient, return a structured failure — never fake success from weak heuristics.
- **Honor the contract.** Outputs should always include `status`. For `report` outputs, also include: `reasonCode`, `failedStep`, `nextAction`, `evidence`, `attemptedStrategies`.

---

## Java Checkstyles

The Java agent uses a lightweight readability baseline:

- `LineLength` is allowed up to `160` characters and is treated as a warning.
- Nested ternary expressions are disallowed.
- `MethodLength` is allowed up to `120` lines and is treated as a warning.
- `ParameterNumber` is allowed up to `12` parameters and is treated as a warning.
- `UnusedImports` and `AvoidStarImport` remain enforced.

A small suppressions file exists for targeted hot spots only:

- `CaptureValueSerializer.java`
- `CapturePreviewView.java`
- `ExecutionPathCollector.java`
- `LineHitVisitor.java`
- `RequestMappingResolver.java`
- `AgentConfig.java`

The goal is to keep the baseline strict enough to prevent noise, but flexible enough to avoid blocking normal development on bootstrap and runtime plumbing.

---

## Typical Dev Flow

1. Copy the example module or package closest to your target framework
2. Rename package, artifact, and plugin identifiers
3. Implement your framework's rules in the mapper or plugin code
4. Validate the module-level build in isolation first
5. Validate end-to-end via `route_synthesis` with `action=create_recipe` (use temporary local registry wiring when testing a plugin before default registration)
6. Only then wire into the default aggregator or registry

Resist the urge to skip to step 6 — the isolated validation steps catch most issues early.

---

## Ready-for-PR Checklist

- [ ] Adapter/plugin returns fail-closed outputs when proof is insufficient
- [ ] Reason codes are specific enough for orchestrator follow-up
- [ ] Paths, verbs, and templates are reproducible from source evidence
- [ ] Module builds cleanly in isolation
- [ ] Default behavior is unchanged unless explicit wiring was added

---

## A Note on "Machine-First" Code

If the contracts here feel unusually strict or mechanical — that's intentional. This system is consumed by agents as much as humans, so deterministic outputs aren't just good practice, they're load-bearing.

That said, clarity for human readers still matters. The best extension work does both: code that's easy to follow, with outputs that agents can act on without ambiguity. You're not writing for one audience or the other — you're writing for both.
