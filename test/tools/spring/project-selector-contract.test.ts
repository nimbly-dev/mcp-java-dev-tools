const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const { registerRouteSynthesisTool } = require("@/tools/core/route_synthesis/handler");
const recipeGenerateDomain = require("@/tools/core/route_synthesis/shared/recipe_generation.util");

type RegisteredToolHandler = (input: Record<string, unknown>) => Promise<{
  structuredContent: Record<string, unknown>;
}>;

const TARGET_INFER_CONFIG = {
  workspaceRootAbs: "C:\\repo",
  workspaceRootSource: "cwd",
  probeBaseUrl: "http://127.0.0.1:9193",
  probeStatusPath: "/__probe/status",
  probeResetPath: "/__probe/reset",
  probeCapturePath: "/__probe/capture",
  probeLineSelectionMaxScanLines: 120,
  probeWaitMaxRetries: 1,
  probeWaitUnreachableRetryEnabled: false,
  probeWaitUnreachableMaxRetries: 3,
};

async function withMockedFetch(
  mockFetch: typeof globalThis.fetch,
  fn: () => Promise<void>,
): Promise<void> {
  const originalFetch = globalThis.fetch;
  if (!originalFetch) throw new Error("global fetch is unavailable in this Node runtime");
  globalThis.fetch = mockFetch;
  try {
    await fn();
  } finally {
    globalThis.fetch = originalFetch;
  }
}

function captureRegisteredHandler(
  registerToolFn: (server: any) => void,
): RegisteredToolHandler {
  let captured: RegisteredToolHandler | undefined;
  const server: any = {
    registerTool: (_name: unknown, _meta: unknown, handler: RegisteredToolHandler) => {
      captured = handler;
    },
  };
  registerToolFn(server);
  assert.equal(typeof captured, "function", "expected tool handler to be registered");
  return captured as RegisteredToolHandler;
}

async function withTempDir(run: (dir: string) => Promise<void>) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "target-infer-contract-"));
  try {
    await run(dir);
  } finally {
    await fs.rm(dir, { recursive: true, force: true });
  }
}

test("route_synthesis create_recipe fails closed when additionalSourceRoots contains an invalid path", async () => {
  const handler = captureRegisteredHandler((server: any) =>
    registerRouteSynthesisTool(server, {
      config: TARGET_INFER_CONFIG,
      probeBaseUrl: "http://127.0.0.1:9193",
      probeStatusPath: "/__probe/status",
      workspaceRootAbs: "C:\\repo",
    }),
  );

  const out = await handler({
    action: "create_recipe",
    input: {
      projectRootAbs: path.resolve(__dirname, "..", ".."),
      classHint: "com.example.CatalogService",
      methodHint: "save",
      intentMode: "regression",
      additionalSourceRoots: ["C:\\definitely\\missing\\source-root"],
    },
  });

  assert.equal(out.structuredContent.status, "project_selector_invalid");
  assert.equal(out.structuredContent.reasonCode, "additional_source_roots_invalid");
  assert.equal(out.structuredContent.nextActionCode, "fix_additional_source_roots");
  assert.equal(out.structuredContent.failedStep, "input_validation");
  assert.equal((out.structuredContent.reasonMeta as any).failedStep, "input_validation");
});

test("route_synthesis infer_target fails closed when additionalSourceRoots exceeds max count", async () => {
  const handler = captureRegisteredHandler((server: any) =>
    registerRouteSynthesisTool(server, {
      config: TARGET_INFER_CONFIG,
      probeBaseUrl: "http://127.0.0.1:9193",
      probeStatusPath: "/__probe/status",
      workspaceRootAbs: "C:\\repo",
    }),
  );

  const roots = Array.from({ length: 11 }, (_, idx) => `src/main/java/module-${idx}`);
  const out = await handler({
    action: "infer_target",
    input: {
      projectRootAbs: path.resolve(__dirname, "..", ".."),
      classHint: "com.example.CatalogService",
      methodHint: "save",
      additionalSourceRoots: roots,
    },
  });

  assert.equal(out.structuredContent.status, "project_selector_invalid");
  assert.equal(out.structuredContent.reasonCode, "additional_source_roots_limit_exceeded");
  assert.equal(out.structuredContent.nextActionCode, "reduce_additional_source_roots");
  assert.equal(out.structuredContent.failedStep, "input_validation");
  assert.equal((out.structuredContent.reasonMeta as any).failedStep, "input_validation");
});

test("route_synthesis create_recipe requires explicit projectRootAbs", async () => {
  const handler = captureRegisteredHandler((server: any) =>
    registerRouteSynthesisTool(server, {
      config: TARGET_INFER_CONFIG,
      probeBaseUrl: "http://127.0.0.1:9193",
      probeStatusPath: "/__probe/status",
      workspaceRootAbs: "C:\\repo",
    }),
  );

  const out = await handler({
    action: "create_recipe",
    input: {
      classHint: "CatalogService",
      methodHint: "save",
      intentMode: "regression",
    },
  });

  assert.equal(out.structuredContent.status, "project_selector_required");
  assert.equal(out.structuredContent.reasonCode, "project_selector_required");
  assert.equal(out.structuredContent.nextActionCode, "provide_project_root");
  assert.equal(out.structuredContent.projectRoot, "(project_root_unset)");
  assert.equal(out.structuredContent.resultType, "report");
});

test("route_synthesis infer_target requires explicit projectRootAbs", async () => {
  const handler = captureRegisteredHandler((server: any) =>
    registerRouteSynthesisTool(server, {
      config: TARGET_INFER_CONFIG,
      probeBaseUrl: "http://127.0.0.1:9193",
      probeStatusPath: "/__probe/status",
      workspaceRootAbs: "C:\\repo",
    }),
  );

  const out = await handler({
    action: "infer_target",
    input: {
      classHint: "CatalogService",
      methodHint: "save",
    },
  });

  assert.equal(out.structuredContent.status, "project_selector_required");
  assert.equal(out.structuredContent.reasonCode, "project_selector_required");
  assert.equal(out.structuredContent.nextActionCode, "provide_project_root");
});

test("route_synthesis create_recipe fails closed when classHint is not an FQCN", async () => {
  const handler = captureRegisteredHandler((server: any) =>
    registerRouteSynthesisTool(server, {
      config: TARGET_INFER_CONFIG,
      probeBaseUrl: "http://127.0.0.1:9193",
      probeStatusPath: "/__probe/status",
      workspaceRootAbs: "C:\\repo",
    }),
  );

  const out = await handler({
    action: "create_recipe",
    input: {
      projectRootAbs: path.resolve(__dirname, "..", ".."),
      classHint: "CatalogService",
      methodHint: "save",
      intentMode: "regression",
    },
  });

  assert.equal(out.structuredContent.status, "class_hint_not_fqcn");
  assert.equal(out.structuredContent.projectRoot, path.resolve(__dirname, "..", ".."));
  assert.equal(typeof out.structuredContent.hints, "object");
  assert.equal(out.structuredContent.reasonCode, "class_hint_not_fqcn");
  assert.equal(out.structuredContent.nextActionCode, "provide_class_fqcn");
  assert.equal(out.structuredContent.failedStep, "input_validation");
  assert.equal((out.structuredContent.reasonMeta as any).failedStep, "input_validation");
  assert.equal(Array.isArray(out.structuredContent.evidence), true);
  assert.equal(Array.isArray(out.structuredContent.attemptedStrategies), true);
  assert.match(out.structuredContent.nextAction, /Provide exact FQCN/i);
});

test("route_synthesis create_recipe passes configured workspace root into generateRecipe", async () => {
  await withTempDir(async (dir: string) => {
    const workspaceRootAbs = path.join(dir, "..", "workspace-root");
    let capturedArgs: Record<string, unknown> | undefined;
    const originalGenerateRecipe = recipeGenerateDomain.generateRecipe;
    recipeGenerateDomain.generateRecipe = async (args: Record<string, unknown>) => {
      capturedArgs = args;
      return {
        requestCandidates: [],
        executionPlan: {
          selectedMode: "regression",
          routingReason: "regression_no_probe",
          steps: [],
          probeCallPlan: {
            total: 0,
            verificationMethod: "probe_wait_for_hit",
            actuated: false,
            byTool: {
              probe_reset: 0,
              probe_wait_for_hit: 0,
              probe_get_status: 0,
              probe_enable: 0,
            },
          },
        },
        resultType: "report",
        status: "api_request_not_inferred",
        selectedMode: "regression",
        lineTargetProvided: false,
        probeIntentRequested: false,
        executionReadiness: "needs_user_input",
        missingInputs: [],
        attemptedStrategies: [],
        evidence: [],
        inferenceDiagnostics: {
          target: { attempted: true, matched: false, candidateCount: 0 },
          request: { attempted: true, matched: false },
        },
        auth: {
          required: "unknown",
          status: "unknown",
          strategy: "unknown",
          nextAction: "none",
          notes: [],
        },
        notes: [],
      };
    };

    try {
      const handler = captureRegisteredHandler((server: any) =>
        registerRouteSynthesisTool(server, {
          config: TARGET_INFER_CONFIG,
          probeBaseUrl: "http://127.0.0.1:9193",
          probeStatusPath: "/__probe/status",
          workspaceRootAbs,
        }),
      );

      await handler({
        action: "create_recipe",
        input: {
          projectRootAbs: dir,
          classHint: "com.example.CatalogController",
          methodHint: "listCatalogShoes",
          intentMode: "regression",
        },
      });
    } finally {
      recipeGenerateDomain.generateRecipe = originalGenerateRecipe;
    }

    assert.equal(capturedArgs?.rootAbs, dir);
    assert.equal(capturedArgs?.workspaceRootAbs, workspaceRootAbs);
  });
});

test("route_synthesis create_recipe fails closed when strict runtime line is unresolved", async () => {
  await withTempDir(async (dir: string) => {
    const originalGenerateRecipe = recipeGenerateDomain.generateRecipe;
    recipeGenerateDomain.generateRecipe = async () => ({
      inferredTarget: {
        file: path.join(dir, "src", "main", "java", "com", "example", "CatalogController.java"),
        key: "com.example.CatalogController#save",
        line: 50,
      },
      requestCandidates: [
        {
          method: "POST",
          path: "/catalog",
          queryTemplate: "",
          fullUrlHint: "/catalog",
          rationale: ["controller mapping"],
        },
      ],
      executionPlan: {
        selectedMode: "single_line_probe",
        routingReason: "single_line_probe",
        steps: [],
        probeCallPlan: {
          total: 2,
          verificationMethod: "probe_wait_for_hit",
          actuated: false,
          byTool: {
            probe_reset: 1,
            probe_wait_for_hit: 1,
            probe_get_status: 0,
            probe_enable: 0,
          },
        },
      },
      resultType: "recipe",
      status: "single_line_probe_ready",
      selectedMode: "single_line_probe",
      lineTargetProvided: true,
      probeIntentRequested: true,
      executionReadiness: "ready",
      missingInputs: [],
      attemptedStrategies: ["spring_entrypoint_resolution"],
      evidence: ["resolver=stub"],
      inferenceDiagnostics: {
        target: { attempted: true, matched: true, candidateCount: 1 },
        request: { attempted: true, matched: true, source: "spring_mvc" },
      },
      auth: {
        required: "unknown",
        status: "ok",
        strategy: "none",
        nextAction: "none",
        notes: [],
      },
      notes: [],
    });

    try {
      const handler = captureRegisteredHandler((server: any) =>
        registerRouteSynthesisTool(server, {
          config: TARGET_INFER_CONFIG,
          probeBaseUrl: "http://127.0.0.1:9193",
          probeStatusPath: "/__probe/status",
          workspaceRootAbs: "C:\\repo",
        }),
      );

      await withMockedFetch(async () => {
        return new Response(
          JSON.stringify({
            key: "com.example.CatalogController#save:50",
            hitCount: 0,
            lastHitEpoch: 0,
            lineResolvable: false,
            lineValidation: "invalid_line_target",
            capturePreview: { available: false },
          }),
          { status: 200, headers: { "content-type": "application/json; charset=utf-8" } },
        );
      }, async () => {
        const out = await handler({
          action: "create_recipe",
          input: {
            projectRootAbs: dir,
            classHint: "com.example.CatalogController",
            methodHint: "save",
            lineHint: 50,
            intentMode: "line_probe",
          },
        });
        assert.equal(out.structuredContent.resultType, "report");
        assert.equal(out.structuredContent.status, "target_not_inferred");
        assert.equal(out.structuredContent.reasonCode, "runtime_line_unresolved");
        assert.equal(out.structuredContent.nextActionCode, "select_resolvable_line");
        assert.equal(out.structuredContent.failedStep, "line_validation");
        assert.equal((out.structuredContent.reasonMeta as any).failedStep, "line_validation");
        assert.equal(out.structuredContent.executionReadiness, "needs_user_input");
      });
    } finally {
      recipeGenerateDomain.generateRecipe = originalGenerateRecipe;
    }
  });
});

test("route_synthesis infer_target requires exact classHint", async () => {
  const handler = captureRegisteredHandler((server: any) =>
    registerRouteSynthesisTool(server, {
      config: TARGET_INFER_CONFIG,
      probeBaseUrl: "http://127.0.0.1:9193",
      probeStatusPath: "/__probe/status",
      workspaceRootAbs: "C:\\repo",
    }),
  );

  const out = await handler({
    action: "infer_target",
    input: {
      projectRootAbs: path.resolve(__dirname, "..", ".."),
      methodHint: "save",
    },
  });

  assert.equal(out.structuredContent.resultType, "report");
  assert.equal(out.structuredContent.status, "class_hint_required");
  assert.equal(out.structuredContent.reasonCode, "class_hint_required");
  assert.equal(out.structuredContent.nextActionCode, "provide_class_hint");
  assert.equal(out.structuredContent.failedStep, "input_validation");
});

test("route_synthesis infer_target emits explicit resultType and status", async () => {
  await withTempDir(async (dir: string) => {
    const handler = captureRegisteredHandler((server: any) =>
      registerRouteSynthesisTool(server, {
        config: TARGET_INFER_CONFIG,
        probeBaseUrl: "http://127.0.0.1:9193",
        probeStatusPath: "/__probe/status",
        workspaceRootAbs: "C:\\repo",
      }),
    );
    const javaFile = path.join(dir, "src", "main", "java", "com", "example", "CatalogService.java");
    await fs.mkdir(path.dirname(javaFile), { recursive: true });
    await fs.writeFile(
      javaFile,
      [
        "package com.example;",
        "public class CatalogService {",
        "  public boolean save() {",
        "    return true;",
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );

    let calls = 0;
    await withMockedFetch(async () => {
      calls += 1;
      return new Response(
        JSON.stringify({
          key: "com.example.CatalogService#save:3",
          hitCount: 0,
          lastHitEpoch: 0,
          lineResolvable: calls === 1 ? false : true,
          lineValidation: calls === 1 ? "invalid_line_target" : "resolvable",
        }),
        { status: 200, headers: { "content-type": "application/json; charset=utf-8" } },
      );
    }, async () => {
      const out = await handler({
        action: "infer_target",
        input: {
          projectRootAbs: dir,
          classHint: "com.example.CatalogService",
          methodHint: "save",
        },
      });

      assert.equal(out.structuredContent.resultType, "ranked_candidates");
      assert.equal(out.structuredContent.status, "ok");
      const candidates = out.structuredContent.candidates as unknown[];
      assert.equal(Array.isArray(candidates), true);
      assert.equal(candidates.length, 1);
      assert.equal((candidates[0] as any).firstExecutableLine, 4);
      assert.equal((candidates[0] as any).lineSelectionStatus, "validated");
      assert.equal((candidates[0] as any).lineSelectionSource, "runtime_probe_validation");
    });
  });
});

test("route_synthesis infer_target fails closed when runtime probe is unreachable", async () => {
  await withTempDir(async (dir: string) => {
    const handler = captureRegisteredHandler((server: any) =>
      registerRouteSynthesisTool(server, {
        config: TARGET_INFER_CONFIG,
        probeBaseUrl: "http://127.0.0.1:9193",
        probeStatusPath: "/__probe/status",
        workspaceRootAbs: "C:\\repo",
      }),
    );
    const javaFile = path.join(dir, "src", "main", "java", "com", "example", "CatalogService.java");
    await fs.mkdir(path.dirname(javaFile), { recursive: true });
    await fs.writeFile(
      javaFile,
      [
        "package com.example;",
        "public class CatalogService {",
        "  public boolean save() {",
        "    return true;",
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );

    await withMockedFetch(async () => {
      throw new Error("fetch failed");
    }, async () => {
      const out = await handler({
        action: "infer_target",
        input: {
          projectRootAbs: dir,
          classHint: "com.example.CatalogService",
          methodHint: "save",
        },
      });

      assert.equal(out.structuredContent.resultType, "report");
      assert.equal(out.structuredContent.status, "runtime_unreachable");
      assert.equal(out.structuredContent.reasonCode, "runtime_unreachable");
      assert.equal(out.structuredContent.nextActionCode, "verify_probe_reachability");
      assert.equal(out.structuredContent.failedStep, "line_validation");
      assert.equal((out.structuredContent.reasonMeta as any).failedStep, "line_validation");
    });
  });
});

test("route_synthesis class_methods returns unresolved line selection when no line is resolvable", async () => {
  await withTempDir(async (dir: string) => {
    const handler = captureRegisteredHandler((server: any) =>
      registerRouteSynthesisTool(server, {
        config: TARGET_INFER_CONFIG,
        probeBaseUrl: "http://127.0.0.1:9193",
        probeStatusPath: "/__probe/status",
        workspaceRootAbs: "C:\\repo",
      }),
    );
    const javaFile = path.join(
      dir,
      "src",
      "main",
      "java",
      "com",
      "example",
      "CatalogService.java",
    );
    await fs.mkdir(path.dirname(javaFile), { recursive: true });
    await fs.writeFile(
      javaFile,
      [
        "package com.example;",
        "public class CatalogService {",
        "  public boolean save() {",
        "    return true;",
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );

    await withMockedFetch(async () => {
      return new Response(
        JSON.stringify({
          key: "com.example.CatalogService#save:3",
          hitCount: 0,
          lastHitEpoch: 0,
          lineResolvable: false,
          lineValidation: "invalid_line_target",
        }),
        { status: 200, headers: { "content-type": "application/json; charset=utf-8" } },
      );
    }, async () => {
      const out = await handler({
        action: "class_methods",
        input: {
          projectRootAbs: dir,
          classHint: "com.example.CatalogService",
        },
      });

      assert.equal(out.structuredContent.resultType, "class_methods");
      assert.equal(out.structuredContent.status, "ok");
      const methods = out.structuredContent.methods as Array<Record<string, unknown>>;
      assert.equal(Array.isArray(methods), true);
      assert.equal(methods.length, 1);
      assert.equal(methods[0]?.firstExecutableLine, null);
      assert.equal(methods[0]?.lineSelectionStatus, "unresolved");
      assert.equal(methods[0]?.lineSelectionSource, undefined);
    });
  });
});

test("route_synthesis infer_target uses probeId-specific baseUrl for runtime validation", async () => {
  await withTempDir(async (dir: string) => {
    const handler = captureRegisteredHandler((server: any) =>
      registerRouteSynthesisTool(server, {
        config: {
          ...TARGET_INFER_CONFIG,
          probeRegistry: {
            configFileAbs: "C:\\repo\\.mcpjvm\\probe-config.json",
            activeProfile: "dev",
            profileSource: "workspace",
            defaultProbeId: "default-service",
            allowNonWrappedExecutable: false,
            probesById: new Map([
              ["default-service", { id: "default-service", baseUrl: "http://127.0.0.1:9193", include: [], exclude: [] }],
              ["visits-service", { id: "visits-service", baseUrl: "http://127.0.0.1:9194", include: [], exclude: [] }],
            ]),
          },
        },
        probeBaseUrl: "http://127.0.0.1:9193",
        probeStatusPath: "/__probe/status",
        workspaceRootAbs: "C:\\repo",
      }),
    );
    const javaFile = path.join(dir, "src", "main", "java", "com", "example", "CatalogService.java");
    await fs.mkdir(path.dirname(javaFile), { recursive: true });
    await fs.writeFile(
      javaFile,
      [
        "package com.example;",
        "public class CatalogService {",
        "  public boolean save() {",
        "    return true;",
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );

    await withMockedFetch(async (input: any) => {
      const url = typeof input === "string" ? input : String(input?.url ?? "");
      assert.match(url, /127\.0\.0\.1:9194/);
      return new Response(
        JSON.stringify({
          key: "com.example.CatalogService#save:3",
          hitCount: 0,
          lastHitEpoch: 0,
          lineResolvable: true,
          lineValidation: "resolvable",
        }),
        { status: 200, headers: { "content-type": "application/json; charset=utf-8" } },
      );
    }, async () => {
      const out = await handler({
        action: "infer_target",
        input: {
          projectRootAbs: dir,
          classHint: "com.example.CatalogService",
          methodHint: "save",
          probeId: "visits-service",
        },
      });
      assert.equal(out.structuredContent.status, "ok");
    });
  });
});

test("route_synthesis create_recipe uses probeId-specific baseUrl for runtime capture enrichment", async () => {
  await withTempDir(async (dir: string) => {
    const originalGenerateRecipe = recipeGenerateDomain.generateRecipe;
    recipeGenerateDomain.generateRecipe = async () => ({
      inferredTarget: {
        file: path.join(dir, "src", "main", "java", "com", "example", "CatalogController.java"),
        key: "com.example.CatalogController#save",
        line: 50,
      },
      requestCandidates: [{ method: "POST", path: "/catalog", queryTemplate: "", fullUrlHint: "/catalog", rationale: ["controller mapping"] }],
      executionPlan: {
        selectedMode: "single_line_probe",
        routingReason: "single_line_probe",
        steps: [],
        probeCallPlan: { total: 2, verificationMethod: "probe_wait_for_hit", actuated: false, byTool: { probe_reset: 1, probe_wait_for_hit: 1, probe_get_status: 0, probe_enable: 0 } },
      },
      resultType: "recipe",
      status: "single_line_probe_ready",
      selectedMode: "single_line_probe",
      lineTargetProvided: true,
      probeIntentRequested: true,
      executionReadiness: "ready",
      missingInputs: [],
      attemptedStrategies: ["spring_entrypoint_resolution"],
      evidence: ["resolver=stub"],
      inferenceDiagnostics: { target: { attempted: true, matched: true, candidateCount: 1 }, request: { attempted: true, matched: true, source: "spring_mvc" } },
      auth: { required: "unknown", status: "ok", strategy: "none", nextAction: "none", notes: [] },
      notes: [],
    });

    try {
      const handler = captureRegisteredHandler((server: any) =>
        registerRouteSynthesisTool(server, {
          config: TARGET_INFER_CONFIG,
          probeBaseUrl: "http://127.0.0.1:9193",
          probeStatusPath: "/__probe/status",
          workspaceRootAbs: "C:\\repo",
          getProbeRegistry: () => ({
            configFileAbs: "C:\\repo\\.mcpjvm\\probe-config.json",
            activeProfile: "dev",
            profileSource: "workspace",
            defaultProbeId: "default-service",
            allowNonWrappedExecutable: false,
            probesById: new Map([
              ["default-service", { id: "default-service", baseUrl: "http://127.0.0.1:9193", include: [], exclude: [] }],
              ["visits-service", { id: "visits-service", baseUrl: "http://127.0.0.1:9194", include: [], exclude: [] }],
            ]),
          }),
        }),
      );

      await withMockedFetch(async (input: any) => {
        const url = typeof input === "string" ? input : String(input?.url ?? "");
        assert.match(url, /127\.0\.0\.1:9194/);
        return new Response(
          JSON.stringify({
            key: "com.example.CatalogController#save:50",
            hitCount: 0,
            lastHitEpoch: 0,
            lineResolvable: true,
            lineValidation: "resolvable",
            capturePreview: { available: false },
          }),
          { status: 200, headers: { "content-type": "application/json; charset=utf-8" } },
        );
      }, async () => {
        const out = await handler({
          action: "create_recipe",
          input: {
            projectRootAbs: dir,
            classHint: "com.example.CatalogController",
            methodHint: "save",
            lineHint: 50,
            intentMode: "line_probe",
            probeId: "visits-service",
          },
        });
        assert.equal(out.structuredContent.resultType, "recipe");
      });
    } finally {
      recipeGenerateDomain.generateRecipe = originalGenerateRecipe;
    }
  });
});


