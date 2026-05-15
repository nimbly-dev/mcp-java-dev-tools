const assert = require("node:assert/strict");
const test = require("node:test");

const {
  applyStepExtract,
  buildReplayPreflight,
  buildTimestampRunId,
  resolvePrerequisiteContext,
  resolveStepTransport,
} = require("@tools-regression-execution-plan-spec/regression_execution_plan_spec.util");

function baseMetadata(overrides = {}) {
  return {
    specVersion: "1.0.0",
    execution: {
      intent: "regression",
      probeVerification: true,
      pinStrictProbeKey: false,
      discoveryPolicy: "allow_discoverable_prerequisites",
      ...overrides,
    },
  };
}

function baseContract(overrides = {}) {
  return {
    targets: [
      {
        type: "class_method",
        selectors: {
          fqcn: "com.example.social.post.app.controller.PostController",
          method: "createPost",
          signature: "(com.example.social.post.api.CreatePostRequest)",
          sourceRoot: "test/fixtures/spring-apps/social-platform/post-service/post-app",
        },
      },
    ],
    prerequisites: [
      {
        key: "tenantId",
        required: true,
        secret: false,
        provisioning: "user_input",
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
            query: { tenantId: "${tenantId}" },
            body: { title: "Hello World!" },
          },
      },
      extract: [{ from: "response.body.id", as: "postId" }],
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
    ...overrides,
  };
}

test("preflight ready when prerequisites are satisfied by defaults and runtime inputs", () => {
  const result = buildReplayPreflight({
    metadata: baseMetadata(),
    contract: baseContract(),
    providedContext: { "auth.bearer": "provided-at-runtime" },
    targetCandidateCount: 1,
  });
  assert.equal(result.status, "ready");
  assert.equal(result.reasonCode, "ok");
  assert.deepEqual(result.missing, []);
  assert.deepEqual(result.discoverablePending, []);
});

test("preflight needs_user_input when required prerequisite has no value and no default", () => {
  const contract = baseContract({
    prerequisites: [
      { key: "tenantId", required: true, secret: false, provisioning: "user_input" },
      { key: "auth.bearer", required: true, secret: true, provisioning: "user_input" },
    ],
  });
  const result = buildReplayPreflight({
    metadata: baseMetadata(),
    contract,
    providedContext: {},
    targetCandidateCount: 1,
  });
  assert.equal(result.status, "needs_user_input");
  assert.equal(result.reasonCode, "missing_prerequisites_user_input");
  assert.deepEqual(result.missing, ["tenantId", "auth.bearer"]);
});

test("preflight blocks when secret prerequisite persists a default value", () => {
  const contract = baseContract({
    prerequisites: [
      {
        key: "tenantId",
        required: true,
        secret: false,
        provisioning: "discoverable",
        discoverySource: "datasource",
      },
      { key: "auth.bearer", required: true, secret: true, provisioning: "user_input", default: "masked" },
    ],
  });
  const result = buildReplayPreflight({
    metadata: baseMetadata(),
    contract,
    providedContext: {},
    targetCandidateCount: 1,
  });

  assert.equal(result.status, "blocked_invalid");
  assert.equal(result.reasonCode, "secret_default_forbidden");
});

test("preflight blocks when discoverable prerequisites are unresolved and policy is disabled", () => {
  const contract = baseContract({
    prerequisites: [
      {
        key: "tenantId",
        required: true,
        secret: false,
        provisioning: "discoverable",
        discoverySource: "datasource",
      },
      { key: "auth.bearer", required: true, secret: true, provisioning: "user_input" },
    ],
  });
  const result = buildReplayPreflight({
    metadata: baseMetadata({ discoveryPolicy: "disabled" }),
    contract,
    providedContext: { "auth.bearer": "ok" },
    targetCandidateCount: 1,
  });

  assert.equal(result.status, "blocked_invalid");
  assert.equal(result.reasonCode, "discoverable_prerequisite_policy_disabled");
});

test("preflight needs_discovery when discoverable prerequisites are unresolved and policy is enabled", () => {
  const contract = baseContract({
    prerequisites: [
      {
        key: "tenantId",
        required: true,
        secret: false,
        provisioning: "discoverable",
        discoverySource: "datasource",
      },
      { key: "auth.bearer", required: true, secret: true, provisioning: "user_input" },
    ],
  });
  const result = buildReplayPreflight({
    metadata: baseMetadata(),
    contract,
    providedContext: { "auth.bearer": "ok" },
    targetCandidateCount: 1,
  });

  assert.equal(result.status, "needs_discovery");
  assert.equal(result.reasonCode, "missing_prerequisites_discoverable");
  assert.deepEqual(result.discoverablePending, ["tenantId"]);
  assert.deepEqual(result.missing, []);
});

test("preflight blocks when discoverable prerequisite omits discoverySource", () => {
  const contract = baseContract({
    prerequisites: [
      {
        key: "tenantId",
        required: true,
        secret: false,
        provisioning: "discoverable",
      },
      { key: "auth.bearer", required: true, secret: true, provisioning: "user_input" },
    ],
  });
  const result = buildReplayPreflight({
    metadata: baseMetadata(),
    contract,
    providedContext: { "auth.bearer": "ok" },
    targetCandidateCount: 1,
  });

  assert.equal(result.status, "blocked_invalid");
  assert.equal(result.reasonCode, "invalid_discoverable_prerequisite");
});

test("preflight returns mixed reason code when user input and discovery are both unresolved", () => {
  const contract = baseContract({
    prerequisites: [
      {
        key: "tenantId",
        required: true,
        secret: false,
        provisioning: "discoverable",
        discoverySource: "datasource",
      },
      { key: "auth.bearer", required: true, secret: true, provisioning: "user_input" },
    ],
  });
  const result = buildReplayPreflight({
    metadata: baseMetadata(),
    contract,
    providedContext: {},
    targetCandidateCount: 1,
  });

  assert.equal(result.status, "needs_user_input");
  assert.equal(result.reasonCode, "missing_prerequisites_mixed");
  assert.deepEqual(result.missing, ["auth.bearer"]);
  assert.deepEqual(result.discoverablePending, ["tenantId"]);
});

test("preflight blocked_invalid when transport protocol key does not match step protocol", () => {
  const contract = baseContract({
    steps: [
      {
        order: 1,
        id: "create_post",
        targetRef: 0,
        protocol: "http",
        transport: {
          grpc: {
            service: "PostService",
            method: "CreatePost",
          },
        },
      },
    ],
  });
  const result = buildReplayPreflight({
    metadata: baseMetadata(),
    contract,
    providedContext: { "auth.bearer": "ok" },
    targetCandidateCount: 1,
  });
  assert.equal(result.status, "blocked_invalid");
  assert.equal(result.reasonCode, "transport_protocol_mismatch");
});

test("preflight blocked_invalid when step does not define expect[]", () => {
  const contract = baseContract({
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
      },
    ],
  });
  const result = buildReplayPreflight({
    metadata: baseMetadata(),
    contract,
    providedContext: { "auth.bearer": "ok" },
    targetCandidateCount: 1,
  });
  assert.equal(result.status, "blocked_invalid");
  assert.equal(result.reasonCode, "step_expectations_missing");
});

test("preflight blocked_invalid when legacy top-level expectations[] is provided", () => {
  const contract = {
    ...baseContract(),
    expectations: [{ type: "outcome_status", equals: "pass" }],
  };
  const result = buildReplayPreflight({
    metadata: baseMetadata(),
    contract,
    providedContext: { "auth.bearer": "ok" },
    targetCandidateCount: 1,
  });
  assert.equal(result.status, "blocked_invalid");
  assert.equal(result.reasonCode, "top_level_expectations_unsupported");
});

test("preflight blocked_ambiguous when multiple target candidates remain", () => {
  const result = buildReplayPreflight({
    metadata: baseMetadata(),
    contract: baseContract(),
    providedContext: { "auth.bearer": "ok" },
    targetCandidateCount: 2,
  });
  assert.equal(result.status, "blocked_ambiguous");
  assert.equal(result.reasonCode, "target_ambiguous");
});

test("preflight stale_plan when pinStrictProbeKey is enabled but strict key is invalid", () => {
  const metadata = baseMetadata({ pinStrictProbeKey: true });
  const contract = baseContract({
    targets: [
      {
        type: "class_method",
        selectors: { fqcn: "com.example.PostController", method: "createPost" },
        runtimeVerification: { strictProbeKey: "invalid" },
      },
    ],
  });
  const result = buildReplayPreflight({
    metadata,
    contract,
    providedContext: { "auth.bearer": "ok" },
    targetCandidateCount: 1,
  });
  assert.equal(result.status, "stale_plan");
  assert.equal(result.reasonCode, "strict_probe_key_invalid");
});

test("preflight blocks correlation when crossPlan=true and correlationSessionId is missing", () => {
  const contract = baseContract({
    correlation: {
      enabled: true,
      crossPlan: true,
      key: { type: "traceId", value: "trace-001" },
      window: { maxWindowMs: 5000 },
      probeIds: ["gateway-service", "user-service"],
      matchPolicy: {
        requireExactKeyMatch: true,
        requireWindowMatch: true,
        ambiguityStrategy: "fail_closed",
      },
    },
  });
  const result = buildReplayPreflight({
    metadata: baseMetadata(),
    contract,
    providedContext: { "auth.bearer": "ok" },
    targetCandidateCount: 1,
  });
  assert.equal(result.status, "blocked_invalid");
  assert.equal(result.reasonCode, "correlation_session_missing");
});

test("preflight blocks correlation when maxWindowMs is invalid", () => {
  const contract = baseContract({
    correlation: {
      enabled: true,
      key: { type: "traceId", value: "trace-001" },
      window: { maxWindowMs: 0 },
      probeIds: ["gateway-service", "user-service"],
      matchPolicy: {
        requireExactKeyMatch: true,
        requireWindowMatch: true,
        ambiguityStrategy: "fail_closed",
      },
    },
  });
  const result = buildReplayPreflight({
    metadata: baseMetadata(),
    contract,
    providedContext: { "auth.bearer": "ok" },
    targetCandidateCount: 1,
  });
  assert.equal(result.status, "blocked_invalid");
  assert.equal(result.reasonCode, "correlation_window_invalid");
});

test("resolvePrerequisiteContext prefers provided values and falls back to defaults", () => {
  const resolved = resolvePrerequisiteContext(
    [
      {
        key: "tenantId",
        required: true,
        secret: false,
        provisioning: "user_input",
        default: "tenant-social-001",
      },
      {
        key: "region",
        required: true,
        secret: false,
        provisioning: "discoverable",
        discoverySource: "runtime_context",
        default: "ap-southeast-1",
      },
      { key: "auth.bearer", required: true, secret: true, provisioning: "user_input" },
    ],
    { tenantId: "tenant-override", "auth.bearer": "runtime-token" },
  );
  assert.equal(resolved.tenantId, "tenant-override");
  assert.equal(resolved.region, "ap-southeast-1");
  assert.equal(resolved["auth.bearer"], "runtime-token");
});

test("resolveStepTransport replaces context placeholders deterministically", () => {
  const step = {
    order: 1,
    id: "create_post",
    targetRef: 0,
    protocol: "http",
    transport: {
      http: {
        method: "POST",
        pathTemplate: "/api/v1/posts/${postId}",
        query: {
          tenantId: "${tenantId}",
        },
      },
    },
  };
  const resolved = resolveStepTransport(step, { tenantId: "tenant-social-001", postId: "post-22" });
  assert.equal(resolved.http.pathTemplate, "/api/v1/posts/post-22");
  assert.equal(resolved.http.query.tenantId, "tenant-social-001");
});

test("applyStepExtract writes extracted values into next-step context", () => {
  const initial = { tenantId: "tenant-social-001" };
  const output = {
    response: {
      body: {
        id: "post-998",
      },
    },
  };
  const next = applyStepExtract(output, [{ from: "response.body.id", as: "postId" }], initial);
  assert.equal(next.tenantId, "tenant-social-001");
  assert.equal(next.postId, "post-998");
});

test("buildTimestampRunId produces sortable timestamp-based run id", () => {
  const runId = buildTimestampRunId(new Date(2026, 3, 17, 21, 42, 11), 1);
  assert.equal(runId, "04-17-2026-09-42-11PM");
});

test("preflight blocks when project context resolver reports missing env key", () => {
  const result = buildReplayPreflight({
    metadata: baseMetadata(),
    contract: baseContract(),
    providedContext: { "auth.bearer": "provided-at-runtime" },
    targetCandidateCount: 1,
    projectContext: {
      status: "blocked",
      reasonCode: "env_key_missing",
      requiredUserAction: ["Set env key AUTH_BEARER_TOKEN before regression."],
    },
  });
  assert.equal(result.status, "needs_user_input");
  assert.equal(result.reasonCode, "env_key_missing");
  assert.deepEqual(result.missing, []);
  assert.deepEqual(result.checks, []);
  assert.equal(result.nextAction, "Set env key AUTH_BEARER_TOKEN before regression.");
  assert.deepEqual(result.requiredUserAction, ["Set env key AUTH_BEARER_TOKEN before regression."]);
});

test("preflight blocks when step condition uses forward step reference", () => {
  const contract = baseContract({
    steps: [
      {
        order: 1,
        id: "create_post",
        targetRef: 0,
        protocol: "http",
        when: {
          all: [{ left: "step[1].status", op: "equals", right: "pass" }],
        },
        transport: {
          http: {
            method: "POST",
            pathTemplate: "/api/v1/posts",
          },
        },
        expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
      },
    ],
  });
  const result = buildReplayPreflight({
    metadata: baseMetadata(),
    contract,
    providedContext: { "auth.bearer": "ok" },
    targetCandidateCount: 1,
  });
  assert.equal(result.status, "blocked_invalid");
  assert.equal(result.reasonCode, "step_condition_forward_reference");
});

