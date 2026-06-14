const assert = require("node:assert/strict");
const test = require("node:test");

const {
  RouteSynthesisInputSchema,
  RouteSynthesisRequestSchema,
} = require("@/models/inputs/route_synthesis/route_synthesis.input.model");
const { ExecutionProfileExportInputSchema } = require("@/models/inputs/execution_profile_export.input.model");
const z = require("zod/v4");

test("route_synthesis schema includes action and typed input", () => {
  const keys = Object.keys(RouteSynthesisInputSchema);
  assert.equal(keys.includes("action"), true);
  assert.equal(keys.includes("input"), true);
});

test("route_synthesis create_recipe input requires projectRootAbs", () => {
  const createRecipeSchema = z.object(RouteSynthesisInputSchema);
  const createRecipeShape = RouteSynthesisRequestSchema.options.find(
    (option: any) => option.shape.action.value === "create_recipe",
  );
  assert.ok(createRecipeShape);
  const recipeKeys = Object.keys(createRecipeShape.shape.input.shape);
  assert.equal(recipeKeys.includes("projectRootAbs"), true);
  assert.equal(recipeKeys.includes("additionalSourceRoots"), true);
  assert.equal(recipeKeys.includes("mappingsBaseUrl"), true);
  assert.equal(recipeKeys.includes("discoveryPreference"), true);

  const recipeRequest = RouteSynthesisRequestSchema.safeParse({
    action: "create_recipe",
    input: {
      projectRootAbs: "C:\\repo\\service",
      classHint: "com.example.CatalogService",
      methodHint: "save",
      intentMode: "regression",
    },
  });
  assert.equal(recipeRequest.success, true);
  assert.equal(createRecipeSchema.safeParse(recipeRequest.data).success, true);
});

test("route_synthesis infer_target input requires projectRootAbs", () => {
  const inferTargetShape = RouteSynthesisRequestSchema.options.find(
    (option: any) => option.shape.action.value === "infer_target",
  );
  assert.ok(inferTargetShape);
  const inferKeys = Object.keys(inferTargetShape.shape.input.shape);
  assert.equal(inferKeys.includes("projectRootAbs"), true);
  assert.equal(inferKeys.includes("additionalSourceRoots"), true);

  const parsed = RouteSynthesisRequestSchema.safeParse({
    action: "infer_target",
    input: {
      projectRootAbs: "C:\\repo\\service",
      classHint: "com.example.CatalogService",
      methodHint: "save",
    },
  });
  assert.equal(parsed.success, true);
});

test("route_synthesis create_recipe accepts line_probe/regression and rejects legacy internal modes", () => {
  const baseInput = {
    action: "create_recipe",
    input: {
      projectRootAbs: "C:\\repo\\service",
      classHint: "com.example.CatalogService",
      methodHint: "save",
    },
  };
  const parsed = RouteSynthesisRequestSchema.safeParse({
    ...baseInput,
    input: {
      ...baseInput.input,
      intentMode: "regression",
    },
  });
  assert.equal(parsed.success, true);
  const parsedLineProbe = RouteSynthesisRequestSchema.safeParse({
    ...baseInput,
    input: {
      ...baseInput.input,
      intentMode: "line_probe",
    },
  });
  assert.equal(parsedLineProbe.success, true);

  const legacy = RouteSynthesisRequestSchema.safeParse({
    ...baseInput,
    input: {
      ...baseInput.input,
      intentMode: "regression_plus_line_probe",
    },
  });
  assert.equal(legacy.success, false);
});

test("route_synthesis create_recipe accepts runtime discovery preference values", () => {
  const parsed = RouteSynthesisRequestSchema.safeParse({
    action: "create_recipe",
    input: {
      projectRootAbs: "C:\\repo\\service",
      classHint: "com.example.CatalogService",
      methodHint: "save",
      intentMode: "regression",
      discoveryPreference: "runtime_first",
      mappingsBaseUrl: "http://127.0.0.1:8080/actuator/mappings",
    },
  });
  assert.equal(parsed.success, true);

  const invalid = RouteSynthesisRequestSchema.safeParse({
    action: "create_recipe",
    input: {
      projectRootAbs: "C:\\repo\\service",
      classHint: "com.example.CatalogService",
      methodHint: "save",
      intentMode: "regression",
      discoveryPreference: "runtime_preferred",
    },
  });
  assert.equal(invalid.success, false);
});

test("execution_profile_export schema accepts mode or type alias and rejects invalid values", () => {
  const exportSchema = z.object(ExecutionProfileExportInputSchema);
  const withMode = exportSchema.safeParse({ executionProfile: "regression-test-run", mode: "sh" });
  assert.equal(withMode.success, true);

  const withType = exportSchema.safeParse({ executionProfile: "regression-test-run", type: "ps1" });
  assert.equal(withType.success, true);

  const invalidMode = exportSchema.safeParse({ executionProfile: "regression-test-run", mode: "bash" });
  assert.equal(invalidMode.success, false);
});
