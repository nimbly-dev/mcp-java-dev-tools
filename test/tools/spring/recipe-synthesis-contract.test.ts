const assert = require("node:assert/strict");
const test = require("node:test");

const { generateRecipe } = require("@/tools/core/route_synthesis/shared/recipe_generation.util");

const okAuth = {
  required: "unknown",
  status: "ok",
  strategy: "none",
  nextAction: "none",
  notes: [],
};

test("recipe domain emits fail-closed synthesis diagnostics", async () => {
  const result = await generateRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      classHint: "CatalogService",
      methodHint: "finalPriceLte",
      intentMode: "regression",
    },
    {
      inferTargetsFn: async () => ({
        scannedJavaFiles: 1,
        candidates: [
          {
            file: "C:\\repo\\service\\src\\main\\java\\CatalogService.java",
            className: "CatalogService",
            methodName: "finalPriceLte",
            line: 42,
            key: "com.example.CatalogService#finalPriceLte",
            reasons: ["exact"],
          },
        ],
      }),
      synthesizerRegistry: {
        synthesize: async () => ({
          status: "report",
          reasonCode: "spring_entrypoint_not_proven",
          failedStep: "spring_entrypoint_resolution",
          nextAction: "Provide controller linkage.",
          evidence: ["call_chain_missing=true"],
          attemptedStrategies: ["spring_annotation_mapping"],
          synthesizerUsed: "spring",
        }),
      },
      resolveAuthForRecipeFn: async () => okAuth,
    },
  );

  assert.equal(result.status, "api_request_not_inferred");
  assert.equal(result.reasonCode, "spring_entrypoint_not_proven");
  assert.equal(result.failedStep, "spring_entrypoint_resolution");
  assert.equal(result.synthesizerUsed, "spring");
  assert.equal(result.applicationType, "spring");
  assert.deepEqual(result.evidence, ["call_chain_missing=true"]);
  assert.deepEqual(result.attemptedStrategies, ["spring_annotation_mapping"]);
});

test("recipe domain preserves resolver-specific synthesis reason codes", async () => {
  const result = await generateRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      classHint: "CatalogService",
      methodHint: "finalPriceLte",
      intentMode: "regression",
    },
    {
      inferTargetsFn: async () => ({
        scannedJavaFiles: 1,
        candidates: [
          {
            file: "C:\\repo\\service\\src\\main\\java\\CatalogService.java",
            className: "CatalogService",
            methodName: "finalPriceLte",
            line: 42,
            key: "com.example.CatalogService#finalPriceLte",
            reasons: ["exact"],
          },
        ],
      }),
      synthesizerRegistry: {
        synthesize: async () => ({
          status: "report",
          reasonCode: "target_type_ambiguous",
          failedStep: "request_mapping_resolution",
          nextAction: "Provide exact module-scoped class target.",
          evidence: ["matched_types=2"],
          attemptedStrategies: ["java_ast_index_lookup", "java_ast_framework_resolution"],
          synthesizerUsed: "spring",
        }),
      },
      resolveAuthForRecipeFn: async () => okAuth,
    },
  );

  assert.equal(result.status, "api_request_not_inferred");
  assert.equal(result.reasonCode, "target_type_ambiguous");
  assert.equal(result.failedStep, "request_mapping_resolution");
  assert.equal(result.synthesizerUsed, "spring");
  assert.equal(result.applicationType, "spring");
  assert.deepEqual(result.evidence, ["matched_types=2"]);
  assert.deepEqual(result.attemptedStrategies, [
    "java_ast_index_lookup",
    "java_ast_framework_resolution",
  ]);
});

