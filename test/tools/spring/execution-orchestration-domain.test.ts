const assert = require("node:assert/strict");
const http = require("node:http");
const test = require("node:test");
const path = require("node:path");
const fs = require("node:fs");

const { executionOrchestrationDomain } = require("@/tools/core/execution_orchestration/domain");
const {
  createTestTempDir,
  writeJson,
} = require("./regression-runtime-suite-executor.fixture");

function listen(server: typeof http.Server.prototype): Promise<number> {
  return new Promise((resolve, reject) => {
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (!address || typeof address === "string") {
        reject(new Error("expected tcp address"));
        return;
      }
      resolve(address.port);
    });
    server.on("error", reject);
  });
}

test("executionOrchestrationDomain resumes persisted suite progress by suiteRunId without rerunning completed plans", async () => {
  const root = createTestTempDir("execution-orchestration-domain-resume");
  const priorProbeConfigEnv = process.env.MCP_PROBE_CONFIG_FILE;
  const server = http.createServer((req: typeof http.IncomingMessage.prototype, res: typeof http.ServerResponse.prototype) => {
    if (req.url === "/a" || req.url === "/b" || req.url === "/c") {
      res.statusCode = 200;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ ok: true }));
      return;
    }
    res.statusCode = 404;
    res.end(JSON.stringify({ ok: false }));
  });

  try {
    const port = await listen(server);
    const projectName = "resume-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          defaults: {
            requestTimeoutMs: 500,
            retryMax: 1,
            orchestrator: {
              resumePollMax: 3,
              resumePollIntervalMs: 10,
              resumePollTimeoutMs: 120_000,
            },
          },
          executionProfiles: [
            {
              executionProfile: "resume-suite",
              executionPolicy: "stop_on_fail",
              plans: [
                { order: 1, planName: "plan-a" },
                { order: 2, planName: "plan-b" },
                { order: 3, planName: "plan-c" },
              ],
            },
          ],
        },
      ],
    });
    writeJson(path.join(root, ".mcpjvm", "probe-config.json"), {
      defaultProfile: "dev",
      profiles: {
        dev: {
          probes: {
            "gateway-service": {
              baseUrl: `http://127.0.0.1:${String(port)}`,
              include: ["org.example.**"],
              exclude: [],
            },
          },
        },
      },
      workspaces: [{ root, profile: "dev" }],
    });
    process.env.MCP_PROBE_CONFIG_FILE = path.join(root, ".mcpjvm", "probe-config.json");

    for (const [planName, routePath] of [
      ["plan-a", "/a"],
      ["plan-b", "/b"],
      ["plan-c", "/c"],
    ] as const) {
      writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", planName, "metadata.json"), {
        specVersion: "1.0.0",
        execution: {
          intent: "regression",
          probeVerification: false,
          pinStrictProbeKey: false,
          discoveryPolicy: "allow_discoverable_prerequisites",
        },
      });
      writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", planName, "contract.json"), {
        targets: [{ type: "class_method", selectors: { fqcn: "org.example.Controller", method: "call", sourceRoot: "src/main/java" } }],
        prerequisites: [
          {
            key: "apiBaseUrl",
            required: true,
            secret: false,
            provisioning: "user_input",
            default: `http://127.0.0.1:${String(port)}`,
          },
        ],
        steps: [
          {
            order: 1,
            id: `${planName}_step`,
            targetRef: 0,
            protocol: "http",
            transport: { http: { method: "GET", pathTemplate: routePath } },
            expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
          },
        ],
      });
    }

    const first = await executionOrchestrationDomain({
      workspaceRootAbs: root,
      action: "execute",
      payload: {
        projectName,
        executionProfile: "resume-suite",
        maxPlansPerCall: 1,
      },
    });

    assert.equal(first.structuredContent.resultType, "execution_orchestration");
    assert.equal(first.structuredContent.status, "in_progress", JSON.stringify(first.structuredContent));
    assert.equal(first.structuredContent.nextPlanOrder, 2);
    const suiteRunId = String(first.structuredContent.suiteRunId ?? "");
    assert.equal(suiteRunId.length > 0, true);
    const firstPlanRuns = Array.isArray(first.structuredContent.planRuns)
      ? first.structuredContent.planRuns
      : [];
    assert.equal(firstPlanRuns.length, 1);
    assert.equal(firstPlanRuns[0]?.planName, "plan-a");

    const second = await executionOrchestrationDomain({
      workspaceRootAbs: root,
      action: "execute",
      payload: {
        projectName,
        executionProfile: "resume-suite",
        suiteRunId,
        maxPlansPerCall: 1,
      },
    });

    assert.equal(second.structuredContent.resultType, "execution_orchestration");
    assert.equal(second.structuredContent.status, "in_progress");
    assert.equal(second.structuredContent.suiteRunId, suiteRunId);
    const secondPlanRuns = Array.isArray(second.structuredContent.planRuns)
      ? second.structuredContent.planRuns
      : [];
    assert.equal(secondPlanRuns.length, 2);
    assert.equal(secondPlanRuns[0]?.planName, "plan-a");
    assert.equal(secondPlanRuns[1]?.planName, "plan-b");
    assert.equal(second.structuredContent.nextPlanOrder, 3);

    const third = await executionOrchestrationDomain({
      workspaceRootAbs: root,
      action: "execute",
      payload: {
        projectName,
        executionProfile: "resume-suite",
        suiteRunId,
        maxPlansPerCall: 1,
      },
    });

    assert.equal(third.structuredContent.resultType, "execution_orchestration");
    assert.equal(third.structuredContent.status, "pass");
    assert.equal(third.structuredContent.suiteRunId, suiteRunId);
    const thirdPlanRuns = Array.isArray(third.structuredContent.planRuns)
      ? third.structuredContent.planRuns
      : [];
    assert.equal(thirdPlanRuns.length, 3);
    assert.equal(thirdPlanRuns[2]?.planName, "plan-c");

    const persisted = JSON.parse(
      fs.readFileSync(
        path.join(root, ".mcpjvm", projectName, "suite-runs", suiteRunId, "execution_orchestration.result.json"),
        "utf8",
      ),
    );
    assert.equal(persisted.status, "pass");
    assert.equal(Array.isArray(persisted.planRuns), true);
    assert.equal(persisted.planRuns.length, 3);
  } finally {
    if (typeof priorProbeConfigEnv === "string") {
      process.env.MCP_PROBE_CONFIG_FILE = priorProbeConfigEnv;
    } else {
      delete process.env.MCP_PROBE_CONFIG_FILE;
    }
    await new Promise<void>((resolve, reject) => server.close((error: Error | undefined) => (error ? reject(error) : resolve())));
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionOrchestrationDomain auto-resumes fresh calls without explicit maxPlansPerCall", async () => {
  const root = createTestTempDir("execution-orchestration-domain-fresh-auto");
  const priorProbeConfigEnv = process.env.MCP_PROBE_CONFIG_FILE;
  const server = http.createServer((req: typeof http.IncomingMessage.prototype, res: typeof http.ServerResponse.prototype) => {
    if (req.url === "/a" || req.url === "/b") {
      res.statusCode = 200;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ ok: true }));
      return;
    }
    res.statusCode = 404;
    res.end(JSON.stringify({ ok: false }));
  });

  try {
    const port = await listen(server);
    const projectName = "fresh-auto-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          defaults: {
            requestTimeoutMs: 500,
            retryMax: 1,
            orchestrator: {
              resumePollMax: 3,
              resumePollIntervalMs: 10,
              resumePollTimeoutMs: 120_000,
            },
          },
          executionProfiles: [
            {
              executionProfile: "auto-suite",
              executionPolicy: "stop_on_fail",
              plans: [
                { order: 1, planName: "plan-a" },
                { order: 2, planName: "plan-b" },
              ],
            },
          ],
        },
      ],
    });
    writeJson(path.join(root, ".mcpjvm", "probe-config.json"), {
      defaultProfile: "dev",
      profiles: {
        dev: {
          probes: {
            "gateway-service": {
              baseUrl: `http://127.0.0.1:${String(port)}`,
              include: ["org.example.**"],
              exclude: [],
            },
          },
        },
      },
      workspaces: [{ root, profile: "dev" }],
    });
    process.env.MCP_PROBE_CONFIG_FILE = path.join(root, ".mcpjvm", "probe-config.json");

    for (const [planName, routePath] of [
      ["plan-a", "/a"],
      ["plan-b", "/b"],
    ] as const) {
      writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", planName, "metadata.json"), {
        specVersion: "1.0.0",
        execution: {
          intent: "regression",
          probeVerification: false,
          pinStrictProbeKey: false,
          discoveryPolicy: "allow_discoverable_prerequisites",
        },
      });
      writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", planName, "contract.json"), {
        targets: [{ type: "class_method", selectors: { fqcn: "org.example.Controller", method: "call", sourceRoot: "src/main/java" } }],
        prerequisites: [
          {
            key: "apiBaseUrl",
            required: true,
            secret: false,
            provisioning: "user_input",
            default: `http://127.0.0.1:${String(port)}`,
          },
        ],
        steps: [
          {
            order: 1,
            id: `${planName}_step`,
            targetRef: 0,
            protocol: "http",
            transport: { http: { method: "GET", pathTemplate: routePath } },
            expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
          },
        ],
      });
    }

    const out = await executionOrchestrationDomain({
      workspaceRootAbs: root,
      action: "execute",
      payload: {
        projectName,
        executionProfile: "auto-suite",
      },
    });

    assert.equal(out.structuredContent.resultType, "execution_orchestration");
    assert.equal(out.structuredContent.status, "pass");
    const planRuns = Array.isArray(out.structuredContent.planRuns) ? out.structuredContent.planRuns : [];
    assert.equal(planRuns.length, 2);
    assert.equal(planRuns[0]?.planName, "plan-a");
    assert.equal(planRuns[1]?.planName, "plan-b");
  } finally {
    if (typeof priorProbeConfigEnv === "string") {
      process.env.MCP_PROBE_CONFIG_FILE = priorProbeConfigEnv;
    } else {
      delete process.env.MCP_PROBE_CONFIG_FILE;
    }
    await new Promise<void>((resolve, reject) => server.close((error: Error | undefined) => (error ? reject(error) : resolve())));
    fs.rmSync(root, { recursive: true, force: true });
  }
});
