const assert = require("node:assert/strict");
const test = require("node:test");

const {
  applyStepExtract,
  applyStepExtractWithDiagnostics,
  buildReplayPreflight,
  buildTimestampRunId,
  resolveWatcherWaitPolicy,
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

test("preflight accepts correlation without session scope when correlationSessionId is omitted", () => {
  const contract = baseContract({
    correlation: {
      enabled: true,
      crossPlan: false,
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
  assert.equal(result.status, "ready");
  assert.equal(result.reasonCode, "ok");
});

test("preflight blocks cross-plan correlation when correlationSessionId is omitted", () => {
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

test("preflight accepts watchers with step dependency and generic provider shape", () => {
  const contract = baseContract({
    watchers: [
      {
        id: "post_indexed",
        dependency: { stepOrder: 1 },
        provider: {
          type: "http",
          transport: {
            request: {
              method: "GET",
              url: "http://127.0.0.1:9200/posts/${postId}",
            },
          },
        },
        expect: [
          {
            id: "watcher_outcome_pass",
            actualPath: "status",
            operator: "outcome_status",
            expected: "pass",
          },
        ],
      },
    ],
  });
  const result = buildReplayPreflight({
    metadata: baseMetadata(),
    contract,
    providedContext: { "auth.bearer": "ok" },
    targetCandidateCount: 1,
  });
  assert.equal(result.status, "ready");
  assert.equal(result.reasonCode, "ok");
});

test("resolveWatcherWaitPolicy inherits project defaults when watcher overrides are absent", () => {
  const resolved = resolveWatcherWaitPolicy({
    watcher: {},
    providedContext: {
      "runtime.requestTimeoutMs": 4500,
      "runtime.retryMax": 3,
    },
  });
  assert.equal(resolved.timeoutMs, 4500);
  assert.equal(resolved.timeoutSource, "project_default");
  assert.equal(resolved.retryMax, 3);
  assert.equal(resolved.retrySource, "project_default");
});

test("resolveWatcherWaitPolicy prefers explicit watcher waitPolicy overrides", () => {
  const resolved = resolveWatcherWaitPolicy({
    watcher: {
      waitPolicy: {
        timeoutMs: 1200,
        retryMax: 8,
      },
    },
    providedContext: {
      "runtime.requestTimeoutMs": 4500,
      "runtime.retryMax": 3,
    },
  });
  assert.equal(resolved.timeoutMs, 1200);
  assert.equal(resolved.timeoutSource, "watcher_override");
  assert.equal(resolved.retryMax, 8);
  assert.equal(resolved.retrySource, "watcher_override");
});

test("preflight blocks watcher when id is missing", () => {
  const contract = baseContract({
    watchers: [
      {
        id: " ",
        dependency: { stepOrder: 1 },
        provider: { type: "probe", config: { probeId: "search-service" } },
        expect: [{ id: "watcher_outcome_pass", actualPath: "status", operator: "outcome_status", expected: "pass" }],
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
  assert.equal(result.reasonCode, "watcher_id_invalid");
});

test("preflight blocks watcher when dependency stepOrder does not exist", () => {
  const contract = baseContract({
    watchers: [
      {
        id: "post_indexed",
        dependency: { stepOrder: 2 },
        provider: { type: "probe", config: { probeId: "search-service" } },
        expect: [{ id: "watcher_outcome_pass", actualPath: "status", operator: "outcome_status", expected: "pass" }],
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
  assert.equal(result.reasonCode, "watcher_dependency_invalid");
});

test("preflight blocks watcher when provider shape is incomplete", () => {
  const contract = baseContract({
    watchers: [
      {
        id: "post_indexed",
        dependency: { stepOrder: 1 },
        provider: { type: "http" },
        expect: [{ id: "watcher_outcome_pass", actualPath: "status", operator: "outcome_status", expected: "pass" }],
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
  assert.equal(result.reasonCode, "watcher_provider_invalid");
});

test("preflight blocks watcher when waitPolicy values are invalid", () => {
  const contract = baseContract({
    watchers: [
      {
        id: "post_indexed",
        dependency: { stepOrder: 1 },
        provider: { type: "http", config: { endpoint: "watch" } },
        waitPolicy: { timeoutMs: 0 },
        expect: [{ id: "watcher_outcome_pass", actualPath: "status", operator: "outcome_status", expected: "pass" }],
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
  assert.equal(result.reasonCode, "watcher_wait_policy_invalid");
});

test("preflight blocks watcher when expect[] is missing", () => {
  const contract = baseContract({
    watchers: [
      {
        id: "post_indexed",
        dependency: { stepOrder: 1 },
        provider: { type: "http", config: { endpoint: "watch" } },
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
  assert.equal(result.reasonCode, "watcher_expectations_missing");
});

test("preflight blocks watcher when expectation shape is invalid", () => {
  const contract = baseContract({
    watchers: [
      {
        id: "post_indexed",
        dependency: { stepOrder: 1 },
        provider: { type: "http", config: { endpoint: "watch" } },
        expect: [{ id: "watcher_outcome_pass", actualPath: "status", operator: "outcome_status" }],
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
  assert.equal(result.reasonCode, "watcher_expectation_invalid");
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

test("resolvePrerequisiteContext normalizes legacy baseUrl to canonical apiBaseUrl", () => {
  const resolved = resolvePrerequisiteContext(
    [
      {
        key: "baseUrl",
        required: true,
        secret: false,
        provisioning: "user_input",
        default: "http://127.0.0.1:8082",
      },
    ],
    {},
  );
  assert.equal(resolved.baseUrl, "http://127.0.0.1:8082");
  assert.equal(resolved.apiBaseUrl, "http://127.0.0.1:8082");
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
      body: "{\"id\":\"post-998\"}",
      bodyJson: {
        id: "post-998",
      },
    },
  };
  const next = applyStepExtract(output, [{ from: "response.bodyJson.id", as: "postId" }], initial);
  assert.equal(next.tenantId, "tenant-social-001");
  assert.equal(next.postId, "post-998");
});

test("applyStepExtract supports array index notation in extract paths", () => {
  const initial = { tenantId: "tenant-social-001" };
  const output = {
    response: {
      bodyJson: {
        names: [
          { locale: "*", value: "Test" },
          { locale: "en", value: "Test EN" },
        ],
      },
    },
  };
  const next = applyStepExtract(output, [{ from: "response.bodyJson.names[0].value", as: "primaryName" }], initial);
  assert.equal(next.tenantId, "tenant-social-001");
  assert.equal(next.primaryName, "Test");
});

test("applyStepExtractWithDiagnostics records unresolved extract without mutating context", () => {
  const initial = { tenantId: "tenant-social-001" };
  const output = {
    response: {
      body: "{\"ok\":true}",
      bodyJson: {
        ok: true,
      },
    },
  };
  const result = applyStepExtractWithDiagnostics(
    output,
    [{ from: "response.body.id", as: "triggeredEventId" }],
    initial,
  );
  assert.equal(result.hasRequiredUnresolved, false);
  assert.deepEqual(result.context, initial);
  assert.deepEqual(result.outcomes, [
    {
      from: "response.body.id",
      as: "triggeredEventId",
      required: false,
      status: "unresolved",
      reasonCode: "extract_path_missing",
    },
  ]);
});

test("preflight blocks when extract entry is malformed", () => {
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
        extract: [{ from: "response.bodyJson.id", as: "postId", required: "yes" }],
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
  });
  const result = buildReplayPreflight({
    metadata: baseMetadata(),
    contract,
    providedContext: { "auth.bearer": "provided-at-runtime" },
    targetCandidateCount: 1,
  });
  assert.equal(result.status, "blocked_invalid");
  assert.equal(result.reasonCode, "step_extract_invalid");
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

test("preflight accepts compatible {{key}} transport placeholder syntax before execution", () => {
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
            url: "{{targetBaseUrl}}/api/v1/posts",
            headers: {
              Authorization: "Bearer {{auth.bearer}}",
            },
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
  assert.equal(result.status, "ready");
  assert.equal(result.reasonCode, "ok");
});

test("preflight blocks raw env-style prerequisite keys and requires canonical context keys", () => {
  const contract = baseContract({
    prerequisites: [{ key: "AUTH_BEARER_TOKEN", required: true, secret: true, provisioning: "user_input" }],
    steps: [
      {
        order: 1,
        id: "create_post",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "POST",
            url: "http://127.0.0.1/api/v1/posts",
          },
        },
        expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
      },
    ],
  });
  const result = buildReplayPreflight({
    metadata: baseMetadata(),
    contract,
    providedContext: {},
    targetCandidateCount: 1,
  });
  assert.equal(result.status, "blocked_invalid");
  assert.equal(result.reasonCode, "plan_context_key_noncanonical");
  assert.match(result.requiredUserAction[0], /AUTH_BEARER_TOKEN/);
});

test("preflight blocks raw env-style transport placeholder keys and requires canonical context keys", () => {
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
            url: "http://127.0.0.1/api/v1/posts",
            headers: {
              Authorization: "Bearer {{AUTH_BEARER_TOKEN}}",
            },
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
  assert.equal(result.reasonCode, "plan_context_key_noncanonical");
  assert.match(result.requiredUserAction[0], /AUTH_BEARER_TOKEN/);
});

test("preflight accepts compatible spaced {{ key }} transport placeholder syntax before execution", () => {
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
            url: "{{ targetBaseUrl }}/api/v1/posts",
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
  assert.equal(result.status, "ready");
  assert.equal(result.reasonCode, "ok");
});

test("preflight accepts compatible nested {{key}} placeholder syntax inside transport body", () => {
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
            url: "${tenantId}/api/v1/posts",
            body: {
              items: ["{{targetBaseUrl}}", { title: "ok" }],
            },
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
  assert.equal(result.status, "ready");
  assert.equal(result.reasonCode, "ok");
});

test("preflight accepts compatible triple-brace {{{key}}} transport placeholder syntax before execution", () => {
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
            url: "{{{targetBaseUrl}}}/api/v1/posts",
            headers: {
              Authorization: "Bearer {{{auth.bearer}}}",
            },
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
  assert.equal(result.status, "ready");
  assert.equal(result.reasonCode, "ok");
});

test("preflight blocks malformed transport placeholder syntax with field diagnostics", () => {
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
            url: "{{ targetBaseUrl }/api/v1/posts",
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
  assert.equal(result.reasonCode, "transport_placeholder_syntax_invalid");
  assert.match(result.requiredUserAction[0], /transport\.http\.url/);
});

test("preflight still accepts canonical ${key} placeholder syntax in nested transport fields", () => {
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
            url: "${tenantId}/api/v1/posts",
            headers: {
              Authorization: "Bearer ${auth.bearer}",
            },
            body: {
              meta: {
                target: "${tenantId}",
              },
            },
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
  assert.equal(result.status, "ready");
  assert.equal(result.reasonCode, "ok");
});

test("resolveStepTransport resolves both ${key} and {{key}} placeholder forms deterministically", () => {
  const step = {
    order: 1,
    id: "create_post",
    targetRef: 0,
    protocol: "http",
    transport: {
      http: {
        method: "POST",
        pathTemplate: "{{ apiBaseUrl }}/api/v1/posts/${postId}",
        headers: {
          Authorization: "Bearer {{ auth.bearer }}",
        },
        query: {
          tenantId: "${ tenantId }",
        },
      },
    },
  };
  const resolved = resolveStepTransport(step, {
    apiBaseUrl: "http://127.0.0.1:8080",
    tenantId: "tenant-social-001",
    postId: "post-22",
    "auth.bearer": "token-123",
  });
  assert.equal(resolved.http.pathTemplate, "http://127.0.0.1:8080/api/v1/posts/post-22");
  assert.equal(resolved.http.headers.Authorization, "Bearer token-123");
  assert.equal(resolved.http.query.tenantId, "tenant-social-001");
});

test("resolveStepTransport resolves triple-brace {{{key}}} placeholder form deterministically", () => {
  const step = {
    order: 1,
    id: "create_post",
    targetRef: 0,
    protocol: "http",
    transport: {
      http: {
        method: "GET",
        url: "{{{ apiBaseUrl }}}/api/v1/posts/{{{ postId }}}",
        headers: {
          Authorization: "Bearer {{{ auth.bearer }}}",
        },
      },
    },
  };
  const resolved = resolveStepTransport(step, {
    apiBaseUrl: "http://127.0.0.1:8080",
    postId: "post-22",
    "auth.bearer": "token-123",
  });
  assert.equal(resolved.http.url, "http://127.0.0.1:8080/api/v1/posts/post-22");
  assert.equal(resolved.http.headers.Authorization, "Bearer token-123");
});

