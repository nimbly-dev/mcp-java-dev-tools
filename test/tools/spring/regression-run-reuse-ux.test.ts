const assert = require("node:assert/strict");
const test = require("node:test");

const { buildReplayPreflight } = require("@tools-regression-execution-plan-spec/regression_execution_plan_spec.util");
const {
  buildReplayUserMessage,
  resolveReplayInvocation,
} = require("@tools-feature-regression-suite");

function baseMetadata() {
  return {
    specVersion: "1.0.0",
    execution: {
      intent: "regression",
      probeVerification: true,
      pinStrictProbeKey: false,
      discoveryPolicy: "allow_discoverable_prerequisites",
    },
  };
}

function baseContract() {
  return {
    targets: [
      {
        type: "class_method",
        selectors: {
          fqcn: "com.example.social.post.app.controller.PostController",
          method: "createPost",
        },
      },
    ],
    prerequisites: [
      {
        key: "tenantId",
        required: true,
        secret: false,
        provisioning: "discoverable",
        discoverySource: "datasource",
        default: "tenant-social-001",
      },
      { key: "auth.bearer", required: true, secret: true, provisioning: "user_input" },
    ],
    steps: [
      {
        order: 1,
        id: "create_post",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "POST",
            pathTemplate: "/api/v1/posts",
          },
        },
        expect: [
          {
            id: "step_outcome_pass",
            actualPath: "status",
            operator: "outcome_status",
            expected: "pass",
          },
        ],
      },
    ],
  };
}

test("resolveReplayInvocation applies deterministic precedence run_id > plan_path > plan_name > latest", () => {
  const result = resolveReplayInvocation({
    runId: "2026-04-17T09-42-11Z_01",
    planPath: ".mcpjvm/test-project/plans/regression/post-lifecycle-runtime-auto",
    planName: "post-lifecycle-runtime-auto",
    latest: true,
  });
  assert.equal(result.status, "resolved");
  assert.equal(result.selected.kind, "run_id");
  assert.equal(result.selected.value, "2026-04-17T09-42-11Z_01");
  assert.deepEqual(result.ignored, ["plan_path", "plan_name", "latest"]);
});

test("resolveReplayInvocation fails closed when no replay reference is provided", () => {
  const result = resolveReplayInvocation({});
  assert.equal(result.status, "blocked_invalid");
  assert.equal(result.reasonCode, "replay_reference_missing");
  assert.equal(result.selected, null);
});

test("resolveReplayInvocation fails closed when runId is malformed", () => {
  const result = resolveReplayInvocation({ runId: "2026/04/17-01" });
  assert.equal(result.status, "blocked_invalid");
  assert.equal(result.reasonCode, "invalid_run_id");
});

test("buildReplayUserMessage returns ready_to_execute for preflight ready", () => {
  const preflight = buildReplayPreflight({
    metadata: baseMetadata(),
    contract: baseContract(),
    providedContext: { "auth.bearer": "runtime-token" },
    targetCandidateCount: 1,
  });
  const message = buildReplayUserMessage(preflight, {
    kind: "plan_name",
    value: "post-lifecycle-runtime-auto",
  });
  assert.equal(message.status, "ready_to_execute");
  assert.equal(message.reasonCode, "ok");
  assert.equal(message.summary, "Replay preflight ready for plan_name:post-lifecycle-runtime-auto.");
  assert.deepEqual(message.missing, []);
});

test("buildReplayUserMessage returns blocked with unresolved keys for needs_user_input", () => {
  const preflight = buildReplayPreflight({
    metadata: baseMetadata(),
    contract: baseContract(),
    providedContext: {},
    targetCandidateCount: 1,
  });
  const message = buildReplayUserMessage(
    preflight,
    { kind: "latest" },
    {
      selectedPlanPath: ".mcpjvm/test-project/plans/regression/post-lifecycle-runtime-auto",
      selectedRunId: "2026-04-17T09-42-11Z_01",
    },
  );
  assert.equal(message.status, "blocked");
  assert.equal(message.preflightStatus, "needs_user_input");
  assert.deepEqual(message.missing, ["auth.bearer"]);
  assert.equal(
    message.summary,
    "Replay preflight blocked (needs_user_input) for latest run (plan=.mcpjvm/test-project/plans/regression/post-lifecycle-runtime-auto, run=2026-04-17T09-42-11Z_01).",
  );
  assert.deepEqual(message.nextActions, ["Provide auth.bearer"]);
});
