const assert = require("node:assert/strict");
const test = require("node:test");

const { generateRecipe } = require("@tools-feature-route-synthesis/shared/recipe_generation.util");

const okAuth = {
  required: "unknown",
  status: "ok",
  strategy: "none",
  nextAction: "none",
  notes: [],
};

test("fails closed when target is not inferred in regression mode", async () => {
  const result = await generateRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      classHint: "PostController",
      methodHint: "updatePost",
      intentMode: "regression",
    },
    {
      inferTargetsFn: async () => ({
        scannedJavaFiles: 20,
        candidates: [],
      }),
      synthesizerRegistry: {
        synthesize: async () => ({
          status: "recipe",
          synthesizerUsed: "spring",
          framework: "spring",
          requestCandidate: {
            method: "PUT",
            path: "/api/v1/posts/1",
            queryTemplate: "",
            fullUrlHint: "/api/v1/posts/1",
            rationale: ["fallback"],
          },
          trigger: {
            kind: "http",
            method: "PUT",
            path: "/api/v1/posts/1",
            queryTemplate: "",
            fullUrlHint: "/api/v1/posts/1",
            headers: {},
          },
          requestSource: "spring_mvc",
          evidence: ["resolver=stub"],
          attemptedStrategies: ["stub_strategy"],
        }),
      },
      resolveAuthForRecipeFn: async () => okAuth,
    },
  );

  assert.equal(result.status, "target_not_inferred");
  assert.equal(result.resultType, "report");
  assert.equal(result.requestCandidates.length, 0);
  assert.equal(result.failurePhase, "target_inference");
  assert.equal(result.reasonCode, "target_candidate_missing");
  assert.equal(result.failedStep, "target_inference");
  assert.equal(result.inferenceDiagnostics.target.matched, false);
  assert.equal(result.inferenceDiagnostics.request.attempted, true);
  assert.equal(result.inferenceDiagnostics.request.matched, false);
});

test("promotes exact zero-method class match into synthesis fallback for inherited methods", async () => {
  let seenInferredTargetFileAbs;
  const result = await generateRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      classHint: "com.example.web.AppController",
      methodHint: "listApps",
      intentMode: "regression",
    },
    {
      inferTargetsFn: async () => ({
        scannedJavaFiles: 12,
        candidates: [],
      }),
      discoverClassMethodsFn: async () => ({
        scannedJavaFiles: 12,
        matchMode: "exact",
        classes: [
          {
            file: "C:\\repo\\service\\src\\main\\java\\com\\example\\web\\AppController.java",
            className: "AppController",
            fqcn: "com.example.web.AppController",
            methods: [],
          },
        ],
      }),
      synthesizerRegistry: {
        synthesize: async (input: any) => {
          seenInferredTargetFileAbs = input.inferredTargetFileAbs;
          return {
            status: "report",
            reasonCode: "spring_entrypoint_not_proven",
            failedStep: "spring_entrypoint_resolution",
            nextAction: "Provide inherited mapping source roots.",
            evidence: ["resolver=stub"],
            attemptedStrategies: ["stub_strategy"],
            synthesizerUsed: "spring",
          };
        },
      },
      resolveAuthForRecipeFn: async () => okAuth,
    },
  );

  assert.equal(result.status, "api_request_not_inferred");
  assert.equal(result.resultType, "report");
  assert.equal(result.reasonCode, "spring_entrypoint_not_proven");
  assert.equal(result.failedStep, "spring_entrypoint_resolution");
  assert.equal(
    seenInferredTargetFileAbs,
    "C:\\repo\\service\\src\\main\\java\\com\\example\\web\\AppController.java",
  );
  assert.equal(result.inferenceDiagnostics.target.matched, true);
  assert.equal(result.inferenceDiagnostics.request.matched, false);
});

test("keeps generic target_not_inferred guidance when class inventory has no exact match", async () => {
  const result = await generateRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      classHint: "com.example.web.AppController",
      methodHint: "listApps",
      intentMode: "regression",
    },
    {
      inferTargetsFn: async () => ({
        scannedJavaFiles: 12,
        candidates: [],
      }),
      discoverClassMethodsFn: async () => ({
        scannedJavaFiles: 12,
        matchMode: "none",
        classes: [],
      }),
      synthesizerRegistry: {
        synthesize: async () => {
          throw new Error("synthesizer should not run when target is not inferred");
        },
      },
      resolveAuthForRecipeFn: async () => okAuth,
    },
  );

  assert.equal(result.status, "target_not_inferred");
  assert.equal(result.reasonCode, "target_candidate_missing");
  assert.equal(
    result.nextAction,
    "Refine classHint/methodHint to exact runtime identifiers (add lineHint for strict probe intent) and rerun route_synthesis with action=create_recipe.",
  );
  assert.equal(result.evidence.includes("class_match=exact"), false);
  assert.equal(result.evidence.includes("method_bodies=0"), false);
  assert.equal(result.inferenceDiagnostics.target.matched, false);
  assert.equal(result.inferenceDiagnostics.request.matched, false);
});

test("keeps generic target_not_inferred guidance when class inventory has multiple matches", async () => {
  const result = await generateRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      classHint: "com.example.web.AppController",
      methodHint: "listApps",
      intentMode: "regression",
    },
    {
      inferTargetsFn: async () => ({
        scannedJavaFiles: 12,
        candidates: [],
      }),
      discoverClassMethodsFn: async () => ({
        scannedJavaFiles: 12,
        matchMode: "exact",
        classes: [
          {
            file: "C:\\repo\\service-a\\src\\main\\java\\com\\example\\web\\AppController.java",
            className: "AppController",
            fqcn: "com.example.web.AppController",
            methods: [],
          },
          {
            file: "C:\\repo\\service-b\\src\\main\\java\\com\\example\\web\\AppController.java",
            className: "AppController",
            fqcn: "com.example.web.AppController",
            methods: [],
          },
        ],
      }),
      synthesizerRegistry: {
        synthesize: async () => {
          throw new Error("synthesizer should not run when target is not inferred");
        },
      },
      resolveAuthForRecipeFn: async () => okAuth,
    },
  );

  assert.equal(result.status, "target_not_inferred");
  assert.equal(result.reasonCode, "target_candidate_missing");
  assert.equal(
    result.nextAction,
    "Refine classHint/methodHint to exact runtime identifiers (add lineHint for strict probe intent) and rerun route_synthesis with action=create_recipe.",
  );
  assert.equal(result.evidence.includes("class_match=exact"), false);
  assert.equal(result.evidence.includes("method_bodies=0"), false);
  assert.equal(result.inferenceDiagnostics.target.matched, false);
  assert.equal(result.inferenceDiagnostics.request.matched, false);
});

test("fails closed when multiple module candidates remain target-ambiguous", async () => {
  const result = await generateRecipe(
    {
      rootAbs: "C:\\repo\\workspace",
      workspaceRootAbs: "C:\\repo",
      classHint: "com.example.social.post.app.controller.PostController",
      methodHint: "listPosts",
      intentMode: "regression",
    },
    {
      inferTargetsFn: async () => ({
        scannedJavaFiles: 48,
        candidates: [
          {
            file: "C:\\repo\\workspace\\post-service\\post-app\\src\\main\\java\\com\\example\\social\\post\\app\\controller\\PostController.java",
            className: "PostController",
            fqcn: "com.example.social.post.app.controller.PostController",
            methodName: "listPosts",
            declarationLine: 20,
            line: 22,
            key: "com.example.social.post.app.controller.PostController#listPosts",
            reasons: ["class fqcn exact match", "method exact match"],
          },
          {
            file: "C:\\repo\\workspace\\user-service\\shadow-app\\src\\main\\java\\com\\example\\social\\post\\app\\controller\\PostController.java",
            className: "PostController",
            fqcn: "com.example.social.post.app.controller.PostController",
            methodName: "listPosts",
            declarationLine: 18,
            line: 20,
            key: "com.example.social.post.app.controller.PostController#listPosts",
            reasons: ["class fqcn exact match", "method exact match"],
          },
        ],
      }),
      discoverClassMethodsFn: async () => ({
        scannedJavaFiles: 48,
        matchMode: "none",
        classes: [],
      }),
      synthesizerRegistry: {
        synthesize: async () => {
          throw new Error("synthesizer should not run when target selection is ambiguous");
        },
      },
      resolveAuthForRecipeFn: async () => okAuth,
    },
  );

  assert.equal(result.status, "target_not_inferred");
  assert.equal(result.resultType, "report");
  assert.equal(result.failurePhase, "target_inference");
  assert.equal(result.failureReasonCode, "target_ambiguous");
  assert.equal(result.reasonCode, "target_ambiguous");
  assert.equal(result.failedStep, "target_selection");
  assert.equal(result.requestCandidates.length, 0);
  assert.equal(result.inferenceDiagnostics.target.matched, false);
  assert.equal(result.inferenceDiagnostics.target.candidateCount, 2);
  assert.deepEqual(result.attemptedStrategies, [
    "target_inference_exact_match",
    "target_selection_disambiguation",
  ]);
});

test("keeps target_not_inferred for probe mode when strict line target is unavailable", async () => {
  const result = await generateRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      classHint: "PostController",
      methodHint: "updatePost",
      lineHint: 88,
      intentMode: "line_probe",
    },
    {
      inferTargetsFn: async () => ({
        scannedJavaFiles: 20,
        candidates: [],
      }),
      synthesizerRegistry: {
        synthesize: async () => ({
          status: "recipe",
          synthesizerUsed: "spring",
          framework: "spring",
          requestCandidate: {
            method: "PUT",
            path: "/api/v1/posts/1",
            queryTemplate: "",
            fullUrlHint: "/api/v1/posts/1",
            rationale: ["fallback"],
          },
          trigger: {
            kind: "http",
            method: "PUT",
            path: "/api/v1/posts/1",
            queryTemplate: "",
            fullUrlHint: "/api/v1/posts/1",
            headers: {},
          },
          requestSource: "spring_mvc",
          evidence: ["resolver=stub"],
          attemptedStrategies: ["stub_strategy"],
        }),
      },
      resolveAuthForRecipeFn: async () => okAuth,
    },
  );

  assert.equal(result.status, "target_not_inferred");
  assert.equal(result.resultType, "report");
  assert.equal(result.failurePhase, "target_inference");
  assert.equal(result.failureReasonCode, "target_candidate_missing");
  assert.equal(result.requestCandidates.length, 0);
  assert.equal(result.executionPlan.probeCallPlan.total, 0);
});

test("reports request_inference failure when target is inferred but request candidate is missing", async () => {
  const result = await generateRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      classHint: "PostController",
      methodHint: "updatePost",
      intentMode: "regression",
    },
    {
      inferTargetsFn: async () => ({
        scannedJavaFiles: 20,
        candidates: [
          {
            file: "C:\\repo\\service\\src\\main\\java\\PostController.java",
            className: "PostController",
            methodName: "updatePost",
            line: 42,
            key: "com.example.social.post.app.controller.PostController#updatePost",
            reasons: ["method exact match"],
          },
        ],
      }),
      synthesizerRegistry: {
        synthesize: async () => ({
          status: "report",
          reasonCode: "request_candidate_missing",
          failedStep: "request_synthesis",
          nextAction: "Refine classHint/methodHint/lineHint.",
          evidence: ["resolver=stub"],
          attemptedStrategies: ["stub_strategy"],
          synthesizerUsed: "spring",
        }),
      },
    },
  );

  assert.equal(result.status, "api_request_not_inferred");
  assert.equal(result.resultType, "report");
  assert.equal(result.failurePhase, "request_inference");
  assert.equal(result.failureReasonCode, "request_candidate_missing");
  assert.equal(result.inferenceDiagnostics.target.matched, true);
  assert.equal(result.inferenceDiagnostics.request.matched, false);
  assert.equal(result.auth.status, "unknown");
  assert.equal(Array.isArray(result.auth.missing), false);
});

test("does not claim missing authToken in report mode when caller already provided auth input", async () => {
  const result = await generateRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      classHint: "PostController",
      methodHint: "updatePost",
      intentMode: "regression",
      authToken: "provided-token",
    },
    {
      inferTargetsFn: async () => ({
        scannedJavaFiles: 20,
        candidates: [
          {
            file: "C:\\repo\\service\\src\\main\\java\\PostController.java",
            className: "PostController",
            methodName: "updatePost",
            line: 42,
            key: "com.example.social.post.app.controller.PostController#updatePost",
            reasons: ["method exact match"],
          },
        ],
      }),
      synthesizerRegistry: {
        synthesize: async () => ({
          status: "report",
          reasonCode: "spring_entrypoint_not_proven",
          failedStep: "spring_entrypoint_resolution",
          nextAction: "Refine classHint/methodHint/lineHint.",
          evidence: ["resolver=stub"],
          attemptedStrategies: ["stub_strategy"],
          synthesizerUsed: "spring",
        }),
      },
    },
  );

  assert.equal(result.resultType, "report");
  assert.equal(result.status, "api_request_not_inferred");
  assert.equal(result.reasonCode, "spring_entrypoint_not_proven");
  assert.equal(result.auth.status, "unknown");
  assert.equal(Array.isArray(result.auth.missing), false);
  assert.equal(
    result.auth.notes.includes(
      "Caller provided auth inputs, but they cannot be validated until route synthesis succeeds.",
    ),
    true,
  );
});

test("reports auth_resolution when request exists but auth input is still required", async () => {
  const result = await generateRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      classHint: "PostController",
      methodHint: "updatePost",
      intentMode: "regression",
    },
    {
      inferTargetsFn: async () => ({
        scannedJavaFiles: 20,
        candidates: [
          {
            file: "C:\\repo\\service\\src\\main\\java\\PostController.java",
            className: "PostController",
            methodName: "updatePost",
            line: 42,
            key: "com.example.social.post.app.controller.PostController#updatePost",
            reasons: ["method exact match"],
          },
        ],
      }),
      synthesizerRegistry: {
        synthesize: async () => ({
          status: "recipe",
          synthesizerUsed: "spring",
          framework: "spring",
          requestCandidate: {
            method: "PUT",
            path: "/api/v1/posts/1",
            queryTemplate: "",
            fullUrlHint: "/api/v1/posts/1",
            rationale: ["controller mapping"],
          },
          trigger: {
            kind: "http",
            method: "PUT",
            path: "/api/v1/posts/1",
            queryTemplate: "",
            fullUrlHint: "/api/v1/posts/1",
            headers: {},
          },
          requestSource: "spring_mvc",
          evidence: ["resolver=stub"],
          attemptedStrategies: ["stub_strategy"],
        }),
      },
      resolveAuthForRecipeFn: async () => ({
        required: true,
        status: "needs_user_input",
        strategy: "bearer",
        missing: ["authToken"],
        nextAction: "Provide authToken",
        notes: [],
      }),
    },
  );

  assert.equal(result.status, "execution_input_required");
  assert.equal(result.resultType, "report");
  assert.equal(result.failurePhase, "auth_resolution");
  assert.equal(result.failureReasonCode, "auth_input_required");
});

test("reports request_confirmation_required when unresolved confirmation blocks execution", async () => {
  const result = await generateRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      classHint: "CatalogController",
      methodHint: "listCatalogShoes",
      intentMode: "regression",
    },
    {
      inferTargetsFn: async () => ({
        scannedJavaFiles: 20,
        candidates: [
          {
            file: "C:\\repo\\service\\src\\main\\java\\CatalogController.java",
            className: "CatalogController",
            methodName: "listCatalogShoes",
            line: 42,
            key: "com.example.CatalogController#listCatalogShoes",
            reasons: ["method exact match"],
          },
        ],
      }),
      synthesizerRegistry: {
        synthesize: async () => ({
          status: "recipe",
          synthesizerUsed: "spring",
          framework: "spring",
          requestCandidate: {
            method: "GET",
            path: "/catalog/shoes",
            queryTemplate: "",
            fullUrlHint: "/catalog/shoes",
            needsConfirmation: ["Confirm endpoint path in current environment."],
            rationale: ["controller mapping"],
          },
          trigger: {
            kind: "http",
            method: "GET",
            path: "/catalog/shoes",
            queryTemplate: "",
            fullUrlHint: "/catalog/shoes",
            headers: {},
          },
          evidence: ["resolver=stub"],
          attemptedStrategies: ["stub_strategy"],
        }),
      },
      resolveAuthForRecipeFn: async () => okAuth,
    },
  );

  assert.equal(result.status, "execution_input_required");
  assert.equal(result.resultType, "report");
  assert.equal(result.failurePhase, "request_inference");
  assert.equal(result.failureReasonCode, "request_confirmation_required");
  assert.equal(result.reasonCode, "request_confirmation_required");
  assert.equal(result.failedStep, "request_confirmation");
});

test("keeps deterministic spring request ready even when informational confirmation note is present", async () => {
  const result = await generateRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      classHint: "CatalogController",
      methodHint: "listCatalogShoes",
      intentMode: "regression",
    },
    {
      inferTargetsFn: async () => ({
        scannedJavaFiles: 20,
        candidates: [
          {
            file: "C:\\repo\\service\\src\\main\\java\\CatalogController.java",
            className: "CatalogController",
            methodName: "listCatalogShoes",
            line: 42,
            key: "com.example.CatalogController#listCatalogShoes",
            reasons: ["method exact match"],
          },
        ],
      }),
      synthesizerRegistry: {
        synthesize: async () => ({
          status: "recipe",
          synthesizerUsed: "spring",
          framework: "spring",
          requestCandidate: {
            method: "GET",
            path: "/catalog/shoes",
            queryTemplate: "",
            fullUrlHint: "/catalog/shoes",
            needsConfirmation: ["Best-effort candidate from controller declaration; confirm mapping/auth before execution."],
            rationale: ["controller mapping"],
          },
          trigger: {
            kind: "http",
            method: "GET",
            path: "/catalog/shoes",
            queryTemplate: "",
            fullUrlHint: "/catalog/shoes",
            headers: {},
          },
          requestSource: "spring_mvc",
          evidence: ["resolver=stub"],
          attemptedStrategies: ["stub_strategy"],
        }),
      },
      resolveAuthForRecipeFn: async () => okAuth,
    },
  );

  assert.equal(result.resultType, "recipe");
  assert.equal(result.status, "regression_ready");
  assert.equal(result.executionReadiness, "ready");
});

test("applies apiBasePath prefix to synthesized request candidate and trigger", async () => {
  const result = await generateRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      classHint: "CatalogController",
      methodHint: "listCatalogShoes",
      apiBasePath: "/api/v1",
      intentMode: "regression",
    },
    {
      inferTargetsFn: async () => ({
        scannedJavaFiles: 20,
        candidates: [
          {
            file: "C:\\repo\\service\\src\\main\\java\\CatalogController.java",
            className: "CatalogController",
            methodName: "listCatalogShoes",
            line: 42,
            key: "com.example.CatalogController#listCatalogShoes",
            reasons: ["method exact match"],
          },
        ],
      }),
      synthesizerRegistry: {
        synthesize: async () => ({
          status: "recipe",
          synthesizerUsed: "spring",
          framework: "spring",
          requestCandidate: {
            method: "GET",
            path: "/catalog/shoes",
            queryTemplate: "page=1",
            fullUrlHint: "/catalog/shoes?page=1",
            rationale: ["controller mapping"],
          },
          trigger: {
            kind: "http",
            method: "GET",
            path: "/catalog/shoes",
            queryTemplate: "page=1",
            fullUrlHint: "/catalog/shoes?page=1",
            headers: {},
          },
          requestSource: "spring_mvc",
          evidence: ["resolver=stub"],
          attemptedStrategies: ["stub_strategy"],
        }),
      },
      resolveAuthForRecipeFn: async () => okAuth,
    },
  );

  assert.equal(result.resultType, "recipe");
  assert.equal(result.requestCandidates[0].path, "/api/v1/catalog/shoes");
  assert.equal(result.requestCandidates[0].fullUrlHint, "/api/v1/catalog/shoes?page=1");
  assert.equal(result.trigger.path, "/api/v1/catalog/shoes");
  assert.equal(result.trigger.fullUrlHint, "/api/v1/catalog/shoes?page=1");
});

test("does not double-prefix apiBasePath when synthesized path already includes it", async () => {
  const result = await generateRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      classHint: "CatalogController",
      methodHint: "listCatalogShoes",
      apiBasePath: "/api/v1",
      intentMode: "regression",
    },
    {
      inferTargetsFn: async () => ({
        scannedJavaFiles: 20,
        candidates: [
          {
            file: "C:\\repo\\service\\src\\main\\java\\CatalogController.java",
            className: "CatalogController",
            methodName: "listCatalogShoes",
            line: 42,
            key: "com.example.CatalogController#listCatalogShoes",
            reasons: ["method exact match"],
          },
        ],
      }),
      synthesizerRegistry: {
        synthesize: async () => ({
          status: "recipe",
          synthesizerUsed: "spring",
          framework: "spring",
          requestCandidate: {
            method: "GET",
            path: "/api/v1/catalog/shoes",
            queryTemplate: "",
            fullUrlHint: "/api/v1/catalog/shoes",
            rationale: ["controller mapping"],
          },
          trigger: {
            kind: "http",
            method: "GET",
            path: "/api/v1/catalog/shoes",
            queryTemplate: "",
            fullUrlHint: "/api/v1/catalog/shoes",
            headers: {},
          },
          requestSource: "spring_mvc",
          evidence: ["resolver=stub"],
          attemptedStrategies: ["stub_strategy"],
        }),
      },
      resolveAuthForRecipeFn: async () => okAuth,
    },
  );

  assert.equal(result.resultType, "recipe");
  assert.equal(result.requestCandidates[0].path, "/api/v1/catalog/shoes");
  assert.equal(result.trigger.path, "/api/v1/catalog/shoes");
});

test("emits non-blocking context path hint when apiBasePath is not provided", async () => {
  const result = await generateRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      classHint: "CatalogController",
      methodHint: "listCatalogShoes",
      intentMode: "regression",
    },
    {
      inferTargetsFn: async () => ({
        scannedJavaFiles: 20,
        candidates: [
          {
            file: "C:\\repo\\service\\src\\main\\java\\CatalogController.java",
            className: "CatalogController",
            methodName: "listCatalogShoes",
            line: 42,
            key: "com.example.CatalogController#listCatalogShoes",
            reasons: ["method exact match"],
          },
        ],
      }),
      synthesizerRegistry: {
        synthesize: async () => ({
          status: "recipe",
          synthesizerUsed: "spring",
          framework: "spring",
          requestCandidate: {
            method: "GET",
            path: "/catalog/shoes",
            queryTemplate: "",
            fullUrlHint: "/catalog/shoes",
            rationale: ["controller mapping"],
          },
          trigger: {
            kind: "http",
            method: "GET",
            path: "/catalog/shoes",
            queryTemplate: "",
            fullUrlHint: "/catalog/shoes",
            headers: {},
          },
          requestSource: "spring_mvc",
          evidence: ["resolver=stub"],
          attemptedStrategies: ["stub_strategy"],
        }),
      },
      resolveAuthForRecipeFn: async () => okAuth,
    },
  );

  assert.equal(result.resultType, "recipe");
  assert.equal(result.executionReadiness, "ready");
  assert.equal(
    result.notes.some((note: string) => note.startsWith("context_path_hint=")),
    true,
  );
});

test("passes additionalSourceRootsAbs into target inference for deterministic cross-root scope", async () => {
  let seenAdditionalRoots: string[] | undefined;
  const result = await generateRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      additionalSourceRootsAbs: ["C:\\repo\\core\\src\\main\\java"],
      classHint: "CatalogController",
      methodHint: "listCatalogShoes",
      intentMode: "regression",
    },
    {
      inferTargetsFn: async (args: any) => {
        seenAdditionalRoots = args.additionalRootsAbs;
        return {
          scannedJavaFiles: 20,
          candidates: [],
        };
      },
      discoverClassMethodsFn: async () => ({
        scannedJavaFiles: 20,
        matchMode: "none",
        classes: [],
      }),
      synthesizerRegistry: {
        synthesize: async () => {
          throw new Error("synthesizer should not run when target is not inferred");
        },
      },
      resolveAuthForRecipeFn: async () => okAuth,
    },
  );

  assert.equal(result.status, "target_not_inferred");
  assert.deepEqual(seenAdditionalRoots, ["C:\\repo\\core\\src\\main\\java"]);
});

test("requests at least two target candidates so ambiguity can be fail-closed", async () => {
  let seenMaxCandidates;
  const result = await generateRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      classHint: "CatalogController",
      methodHint: "listCatalogShoes",
      maxCandidates: 1,
      intentMode: "regression",
    },
    {
      inferTargetsFn: async (args: any) => {
        seenMaxCandidates = args.maxCandidates;
        return {
          scannedJavaFiles: 20,
          candidates: [],
        };
      },
      discoverClassMethodsFn: async () => ({
        scannedJavaFiles: 20,
        matchMode: "none",
        classes: [],
      }),
      synthesizerRegistry: {
        synthesize: async () => {
          throw new Error("synthesizer should not run when target is not inferred");
        },
      },
      resolveAuthForRecipeFn: async () => okAuth,
    },
  );

  assert.equal(result.status, "target_not_inferred");
  assert.equal(seenMaxCandidates, 2);
});




