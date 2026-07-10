const assert = require("node:assert/strict");
const test = require("node:test");

const { buildRecipeTemplateModel } = require("@tools-feature-route-synthesis");

test("recipe template model always reports selected mode", () => {
  const model = buildRecipeTemplateModel({
    classHint: "CatalogService",
    methodHint: "listItems",
    generated: {
      requestCandidates: [
        {
          method: "GET",
          path: "/api/items",
          queryTemplate: "",
          fullUrlHint: "http://127.0.0.1:8080/api/items",
          rationale: ["test"],
        },
      ],
      executionPlan: {
        selectedMode: "regression",
        routingReason: "regression checks",
        steps: [
          {
            phase: "execute",
            title: "Execute regression API check",
            instruction: "GET /api/items",
          },
        ],
      },
      resultType: "recipe",
      status: "regression_ready",
      selectedMode: "regression",
      lineTargetProvided: false,
      probeIntentRequested: false,
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
    },
  });

  assert.equal(model["recipe.mode"], "regression");
  assert.match(model["recipe.steps"], /Selected mode: regression/);
  assert.equal(model["run.notes"].includes("selected_mode=regression"), true);
});

