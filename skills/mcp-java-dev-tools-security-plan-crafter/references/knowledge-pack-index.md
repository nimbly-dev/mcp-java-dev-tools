# Black-box Knowledge-Pack Index

This index is Skill Workflow routing guidance. Detailed machine-readable manifests and rules are independently stored under `tools/features/security-suite/knowledge-packs/<pack-id>/` and loaded by the Security Suite Feature Module.

| Pinned reference           | Routing signal                                                   | Default severity |
| -------------------------- | ---------------------------------------------------------------- | ---------------- |
| `web-api-core@1.0.0`       | HTTP/API cases across declared attack categories                 | `medium`         |
| `authorization-idor@1.0.0` | Authorization cases involving own-versus-foreign resource access | `high`           |

Rules:

- Pin at least one exact `pack@major.minor.patch` reference in `securityKnowledge.packRefs`.
- The loader resolves the exact pack reference, then checks the manifest's contract-version compatibility range before execution.
- Use only declared HTTP/API entrypoints and explicit attack profiles.
- Do not add source, JAR/classpath, FQCN, Sidecar, Probe, or Strict Line Key data to a Black-box contract.
- Updating attack knowledge requires a reviewed versioned pack change; CI does not perform live web research.
