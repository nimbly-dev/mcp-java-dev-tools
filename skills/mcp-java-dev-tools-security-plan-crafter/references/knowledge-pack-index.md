# Black-box Knowledge-Pack Index

This index is Skill Workflow routing guidance. Detailed machine-readable manifests and rules are independently stored under `tools/features/security-suite/knowledge-packs/<pack-id>/` and loaded by the Security Suite Feature Module.

| Pinned reference                   | Routing signal                                                    | Default severity |
| ---------------------------------- | ----------------------------------------------------------------- | ---------------- |
| `web-api-core@1.0.0`               | HTTP/API cases across declared attack categories                  | `medium`         |
| `authorization-idor@1.0.0`         | Authorization cases involving own-versus-foreign resource access  | `high`           |
| `authorization-cross-tenant@1.0.0` | Tenant A identity versus Tenant B resource access                 | `high`           |
| `authentication-boundary@1.0.0`    | Anonymous, missing, malformed, or constrained-role authentication | `high`           |
| `path-traversal@1.0.0`             | Bounded path parameter/query cases within the local target        | `high`           |
| `ssrf-boundary@1.0.0`              | Safe local callback and allow-list URL cases                      | `high`           |
| `file-upload-boundary@1.0.0`       | Safe test-tenant-only upload constraints                          | `high`           |
| `input-injection-boundary@1.0.0`   | Declared-contract bounded SQL/command input cases                 | `critical`       |
| `deserialization-boundary@1.0.0`   | Non-executing serialized-value applicability                      | `critical`       |

Rules:

- Pin at least one exact `pack@major.minor.patch` reference in `securityKnowledge.packRefs`.
- The loader resolves the exact pack reference, then checks the manifest's contract-version compatibility range before execution.
- A catalog with an unavailable, malformed, duplicate, or incompatible pinned pack fails closed before case execution.
- Each selected rule declares its CWE mapping, applicability predicates, finite baseline/attack template, safe mutation boundary, cleanup/max-impact policy, evidence requirements, and deterministic reason codes.
- Use only declared HTTP/API entrypoints and explicit attack profiles.
- Do not add source, JAR/classpath, FQCN, Sidecar, Probe, or Strict Line Key data to a Black-box contract.
- Updating attack knowledge requires a reviewed versioned pack change; CI does not perform live web research.
