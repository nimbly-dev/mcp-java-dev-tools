# Contributing

Thanks for taking the time to contribute to `mcp-java-dev-tools` — it's appreciated.

This project has three contribution tracks, each touching a different part of the system with its own quality bar. Starting in the right track keeps reviews focused and avoids unnecessary churn for everyone.

## Before You Start

A few things that make the process smoother for everyone:

- **Search existing issues** : before opening a new one, it may already be tracked
- **Open an issue first** : for significant features, cross-cutting refactors, or contract changes alignment before code saves time
- **Keep pull requests focused** : don't mix changes across probe internals, request mappers, synthesizers, and docs unless the dependency is real
- **Review your own PR before requesting review** : check correctness, readability, rendering, and failing checks first

## Contribution Tracks

### Track 1: Synthesizers and Adapters

For contributors adding or improving framework support.

**You're in this track if you're working on:**
- Java request mapper adapters
- TypeScript synthesizer plugins
- Framework-specific route extraction or recipe generation
- Request mapping normalization for new frameworks

**Typical locations:**
- [docs/dev/README.md](./docs/dev/README.md)
- [docs/dev/creating-your-own-request-mappers/README.md](./docs/dev/creating-your-own-request-mappers/README.md)
- [docs/dev/creating-your-own-synthesizers/README.md](./docs/dev/creating-your-own-synthesizers/README.md)
- [java-agent/mappers-adapters](./java-agent/mappers-adapters)
- [tools/synthesizers](./tools/synthesizers)

**Design rules:**
- Keep framework-specific behavior out of shared core modules
- Fail closed when route proof is incomplete
- Return deterministic outputs and specific failure metadata
- Don't broaden plugin matching or extractor behavior without strong evidence

**Validation expectations:**
- Java adapter changes should build and test at module scope first
- TypeScript synthesizer changes should pass lint, typecheck, and relevant tests
- End-to-end validation should go through `route_synthesis` with `action=create_recipe` before default registration or wiring changes

---

### Track 2: Probe Tools and Recipe Generation

For contributors working on runtime verification, probe control, tool contracts, orchestration behavior, and Java probe agent extensions.

**You're in this track if you're working on:**
- Java probe agent internals
- Probe HTTP control endpoints
- Capture contracts and redaction behavior
- MCP transport tools
- Recipe generation orchestration
- Line targeting, probe status, reset, capture, and waiting logic
- Contract naming, tool output stability, and fail-closed execution routing

**Typical locations:**
- [java-agent/core](./java-agent/core)
- [tools/core](./tools/core)
- [tools/contracts](./tools/contracts)
- [tools/transport/tools-mcp-server](./tools/transport/tools-mcp-server)
- [docs/architecture](./docs/architecture)

**Design rules:**
- Runtime proof outranks static inference
- Contract changes are high-impact — treat field names, statuses, and failure codes as public interfaces
- Preserve fail-closed behavior: if certainty drops, report it explicitly instead of implying success
- Don't introduce framework-specific branching into probe internals unless there's no cleaner extension point

**Validation expectations:**
- Run TypeScript validation for MCP-side changes
- Run Maven builds for Java agent changes
- For behavioral changes, verify against a live probe workflow when practical
- If you change output contracts, update docs and tests in the same pull request

---

### Track 3: Docs, Design, and Contributor Experience

For contributors improving how people understand, adopt, and use the project. This track matters more than it might seem — good docs reduce friction for every contributor who comes after you.

**You're in this track if you're working on:**
- Contributor documentation and architecture explanations
- Skills and issue-reporting guidance
- Issue and pull request templates
- Installer UX and onboarding clarity
- Output wording, naming clarity, and usability improvements

**Typical locations:**
- [README.md](./README.md)
- [docs](./docs)
- [skills](./skills)
- [scripts/install.sh](./scripts/install.sh)
- [scripts/update.sh](./scripts/update.sh)
- [.github](./.github)

**Design rules:**
- Optimize for clarity before cleverness
- Keep contributor workflows easy to discover from the repo root
- Prefer concrete examples over abstract explanation
- Don't leak sensitive or enterprise-specific information in templates, examples, or screenshots

**Validation expectations:**
- Check Markdown rendering and link accuracy
- Make sure examples match the current tool and skill contracts
- For installer or workflow docs, verify the described steps still reflect the current repo layout

## Build and Test

### TypeScript

```powershell
npm.cmd install
npm.cmd run lint
npm.cmd run typecheck
npm.cmd test
```

### Java Agent

```powershell
mvn -f java-agent\pom.xml test
```

### Full Build

```powershell
npm.cmd run build
npm.cmd run build:java
```

When iterating on a single adapter or Java module, narrower module-level builds are fine. Full-stack validation is still expected before merge when behavior crosses module boundaries.

## TypeScript and STDIO Enforcement Baseline

The repository enforces a TypeScript and MCP stdio baseline through lint/test checks.

- Type-aware linting is enabled and expected to pass.
- Readability hardening rules start as warnings and are promoted to errors after 14 calendar days or 2 merged PR cycles (whichever is earlier).
- MCP stdio behavior is validated by integration coverage to ensure protocol messages stay on `stdout` and diagnostics stay on `stderr`.

Policy owner for baseline thresholds and rollout timing: repository maintainers.

## Java Enforcement Baseline

The repository enforces a Java readability and modular SoC baseline through Maven `verify`.

- Maven Enforcer validates build/runtime baselines and module dependency boundaries.
- Checkstyle enforces Java readability constraints (no nested ternary, no multiple statements per line, method/parameter limits).
- PMD enforces complexity constraints (including cyclomatic complexity).

Policy owner for baseline thresholds and boundary exceptions: repository maintainers.

## Pull Request Expectations

Every pull request should cover four things:

- **What** — what changed
- **Why** — why the change was needed
- **Impact** — what behavior, contracts, docs, or users are affected
- **Testing** — how the change was validated

If tool outputs, schemas, or contracts changed, call that out explicitly. Good pull requests are narrow, reproducible, and honest about trade-offs.

---

## Review Checklist

Before marking your PR ready for review:

- [ ] Change is scoped to the right extension surface
- [ ] Framework-specific logic wasn't pushed into shared core without justification
- [ ] Contract changes are documented
- [ ] Docs were updated if contributor workflows changed
- [ ] Tests and checks pass
- [ ] Markdown renders cleanly for doc changes

## Documentation Changes

If you change contributor-facing workflows, update the relevant docs in the same pull request.

Common docs locations:
- [README.md](./README.md)
- [docs/README.md](./docs/README.md)
- [docs/dev/README.md](./docs/dev/README.md)
- [docs/architecture/synthesis-plugin-contract.md](./docs/architecture/synthesis-plugin-contract.md)
- [docs/architecture/synthesis-failure-codes.md](./docs/architecture/synthesis-failure-codes.md)

## Support Boundaries

Use issues and pull requests for project changes, bugs, and documentation improvements — not for general application debugging unrelated to this project.

If you're reporting a problem, include:
- The affected tool or module
- Expected vs. observed behavior
- Exact reproduction steps
- Sanitized evidence where relevant

## Code of Conduct

By participating in this project, you agree to follow [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md).
