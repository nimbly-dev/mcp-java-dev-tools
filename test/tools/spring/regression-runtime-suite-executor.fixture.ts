const fs = require("node:fs");
const path = require("node:path");

const REPO_ROOT = path.resolve(__dirname, "..", "..", "..");

function createTestTempDir(prefix: string): string {
  const base = path.join(REPO_ROOT, "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  const normalizedPayload =
    path.basename(filePath) === "projects.json" && Array.isArray(payload.workspaces)
      ? {
          ...payload,
          workspaces: payload.workspaces.map((workspace) => {
            const entry = workspace as Record<string, unknown>;
            const defaults =
              entry.defaults && typeof entry.defaults === "object"
                ? (entry.defaults as Record<string, unknown>)
                : {};
            return {
              ...entry,
              defaults: {
                ...defaults,
                orchestrator: {
                  resumePollMax: 30,
                  resumePollIntervalMs: 10000,
                  resumePollTimeoutMs: 300000,
                },
              },
            };
          }),
        }
      : payload;
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(normalizedPayload, null, 2)}\n`, "utf8");
}

function writePlan(root: string, projectName: string, planName: string, routePath: string): void {
  const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
  writeJson(path.join(planRoot, "metadata.json"), {
    specVersion: "1.0.0",
    execution: {
      intent: "regression",
      probeVerification: false,
      pinStrictProbeKey: false,
      discoveryPolicy: "allow_discoverable_prerequisites",
    },
  });
  writeJson(path.join(planRoot, "contract.json"), {
    targets: [{ type: "class_method", selectors: { fqcn: "org.example.Controller", method: "call", sourceRoot: "src/main/java" } }],
    prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
    steps: [
      {
        order: 1,
        id: "step_1",
        targetRef: 0,
        protocol: "http",
        transport: { http: { method: "GET", pathTemplate: routePath } },
        expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
      },
    ],
  });
}

function writeCorrelatedPlan(
  root: string,
  projectName: string,
  planName: string,
  routePath: string,
  args: {
    probeId: string;
    correlationSessionId: string;
    keyValue?: string;
    keySourcePath?: string;
    expectedFlow?: string[];
  },
): void {
  const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
  writeJson(path.join(planRoot, "metadata.json"), {
    specVersion: "1.0.0",
    execution: {
      intent: "regression",
      probeVerification: false,
      pinStrictProbeKey: false,
      discoveryPolicy: "allow_discoverable_prerequisites",
    },
  });
  writeJson(path.join(planRoot, "contract.json"), {
    targets: [
      {
        type: "class_method",
        selectors: { fqcn: "org.example.Controller", method: "call", sourceRoot: "src/main/java" },
        runtimeVerification: { strictProbeKey: "org.example.Controller#call:10", probeId: args.probeId },
      },
    ],
    prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
    steps: [
      {
        order: 1,
        id: "step_1",
        targetRef: 0,
        protocol: "http",
        transport: { http: { method: "GET", pathTemplate: routePath } },
        expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
      },
    ],
    correlation: {
      enabled: true,
      correlationSessionId: args.correlationSessionId,
      key: args.keyValue
        ? { type: "traceId", value: args.keyValue }
        : { type: "traceId", source: { type: "header", path: args.keySourcePath ?? "x-trace-id" } },
      window: { maxWindowMs: 5000 },
      probeIds: [args.probeId],
      ...(args.expectedFlow ? { expectedFlow: args.expectedFlow } : {}),
      matchPolicy: {
        requireExactKeyMatch: true,
        requireWindowMatch: true,
        ambiguityStrategy: "fail_closed",
      },
    },
  });
}

function writeAuthPlan(root: string, projectName: string, planName: string, routePath: string): void {
  const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
  writeJson(path.join(planRoot, "metadata.json"), {
    specVersion: "1.0.0",
    execution: {
      intent: "regression",
      probeVerification: false,
      pinStrictProbeKey: false,
      discoveryPolicy: "allow_discoverable_prerequisites",
    },
  });
  writeJson(path.join(planRoot, "contract.json"), {
    targets: [{ type: "class_method", selectors: { fqcn: "org.example.Controller", method: "call", sourceRoot: "src/main/java" } }],
    prerequisites: [
      { key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" },
      { key: "auth.bearer", required: true, secret: true, provisioning: "user_input" },
    ],
    steps: [
      {
        order: 1,
        id: "step_1",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "GET",
            pathTemplate: routePath,
            headers: { Authorization: "Bearer ${auth.bearer}" },
          },
        },
        expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
      },
    ],
  });
}

function writeAuthenticatedStrictProbeCorrelatedPlan(
  root: string,
  projectName: string,
  planName: string,
  routePath: string,
  args: {
    method: "GET" | "POST";
    probeId: string;
    strictProbeKey: string;
    correlationSessionId: string;
    expectedFlow: string[];
    body?: Record<string, unknown>;
    verifyProbe?: boolean;
    correlationKeyValue?: string;
    correlationSourcePath?: string;
  },
): void {
  const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
  const verifyProbe = args.verifyProbe !== false;
  writeJson(path.join(planRoot, "metadata.json"), {
    specVersion: "1.0.0",
    execution: {
      intent: "regression",
      probeVerification: verifyProbe,
      pinStrictProbeKey: verifyProbe,
      discoveryPolicy: "allow_discoverable_prerequisites",
    },
  });
  writeJson(path.join(planRoot, "contract.json"), {
    targets: [
      {
        type: "class_method",
        selectors: { fqcn: "org.example.Controller", method: "call", sourceRoot: "src/main/java" },
        runtimeVerification: {
          strictProbeKey: args.strictProbeKey,
          probeId: args.probeId,
        },
      },
    ],
    prerequisites: [
      { key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" },
      { key: "auth.bearer", required: true, secret: true, provisioning: "user_input" },
    ],
    steps: [
      {
        order: 1,
        id: "step_1",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: args.method,
            pathTemplate: routePath,
            headers: { Authorization: "Bearer ${auth.bearer}" },
            ...(args.body ? { body: args.body } : {}),
          },
        },
        expect: [
          { id: "http_ok", actualPath: "response.statusCode", operator: "field_equals", expected: 200 },
          ...(verifyProbe
            ? [{ id: "probe_hit", actualPath: "probe.hit", operator: "probe_line_hit", expected: true }]
            : []),
        ],
      },
    ],
    correlation: {
      enabled: true,
      crossPlan: true,
      correlationSessionId: args.correlationSessionId,
      key:
        typeof args.correlationKeyValue === "string"
          ? {
              type: "messageId",
              value: args.correlationKeyValue,
            }
          : {
              type: "messageId",
              source: {
                type: "json_path",
                path: args.correlationSourcePath ?? "response.body.id",
              },
            },
      window: { maxWindowMs: 60000 },
      probeIds: [args.probeId],
      expectedFlow: args.expectedFlow,
      matchPolicy: {
        requireExactKeyMatch: true,
        requireWindowMatch: true,
        ambiguityStrategy: "fail_closed",
      },
    },
  });
}

function writeSadPathPlan(root: string, projectName: string, planName: string, routePath: string): void {
  const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
  writeJson(path.join(planRoot, "metadata.json"), {
    specVersion: "1.0.0",
    execution: {
      intent: "regression",
      probeVerification: false,
      pinStrictProbeKey: false,
      discoveryPolicy: "allow_discoverable_prerequisites",
    },
  });
  writeJson(path.join(planRoot, "contract.json"), {
    targets: [{ type: "class_method", selectors: { fqcn: "org.example.Controller", method: "call", sourceRoot: "src/main/java" } }],
    prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
    steps: [
      {
        order: 1,
        id: "step_1",
        targetRef: 0,
        protocol: "http",
        transport: { http: { method: "GET", pathTemplate: routePath } },
        expect: [
          { id: "http-not-found", actualPath: "response.statusCode", operator: "field_equals", expected: 404 },
          { id: "body-reason", actualPath: "response.body", operator: "contains", expected: "missing" },
        ],
      },
    ],
  });
}

module.exports = {
  createTestTempDir,
  writeJson,
  writePlan,
  writeCorrelatedPlan,
  writeAuthPlan,
  writeAuthenticatedStrictProbeCorrelatedPlan,
  writeSadPathPlan,
};
