const assert = require("node:assert/strict");
const test = require("node:test");

const { RecipeGenerateInputSchema } = require("@/models/inputs/recipe_generate.input.model");
const { TargetInferInputSchema } = require("@/models/inputs/target_infer.input.model");
const { ExecutionProfileExportInputSchema } = require("@/models/inputs/execution_profile_export.input.model");
const z = require("zod/v4");

test("probe_recipe_create schema requires projectRootAbs", () => {
  const keys = Object.keys(RecipeGenerateInputSchema);
  assert.equal(keys.includes("projectRootAbs"), true);
  assert.equal(keys.includes("additionalSourceRoots"), true);
  assert.equal(keys.includes("mappingsBaseUrl"), true);
  assert.equal(keys.includes("discoveryPreference"), true);
});

test("probe_target_infer schema requires projectRootAbs", () => {
  const keys = Object.keys(TargetInferInputSchema);
  assert.equal(keys.includes("projectRootAbs"), true);
  assert.equal(keys.includes("additionalSourceRoots"), true);
});

test("probe_recipe_create schema accepts line_probe/regression and rejects legacy internal modes", () => {
  const recipeSchema = z.object(RecipeGenerateInputSchema);
  const baseInput = {
    projectRootAbs: "C:\\repo\\service",
    classHint: "com.example.CatalogService",
    methodHint: "save",
  };
  const parsed = recipeSchema.safeParse({
    ...baseInput,
    intentMode: "regression",
  });
  assert.equal(parsed.success, true);
  const parsedLineProbe = recipeSchema.safeParse({
    ...baseInput,
    intentMode: "line_probe",
  });
  assert.equal(parsedLineProbe.success, true);

  const legacy = recipeSchema.safeParse({
    projectRootAbs: "C:\\repo\\service",
    classHint: "com.example.CatalogService",
    methodHint: "save",
    intentMode: "regression_plus_line_probe",
  });
  assert.equal(legacy.success, false);
});

test("probe_recipe_create schema accepts runtime discovery preference values", () => {
  const recipeSchema = z.object(RecipeGenerateInputSchema);
  const parsed = recipeSchema.safeParse({
    projectRootAbs: "C:\\repo\\service",
    classHint: "com.example.CatalogService",
    methodHint: "save",
    intentMode: "regression",
    discoveryPreference: "runtime_first",
    mappingsBaseUrl: "http://127.0.0.1:8080/actuator/mappings",
  });
  assert.equal(parsed.success, true);

  const invalid = recipeSchema.safeParse({
    projectRootAbs: "C:\\repo\\service",
    classHint: "com.example.CatalogService",
    methodHint: "save",
    intentMode: "regression",
    discoveryPreference: "runtime_preferred",
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
