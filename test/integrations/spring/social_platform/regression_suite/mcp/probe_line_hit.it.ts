import assert from "node:assert/strict";
import * as fs from "node:fs/promises";
import * as fssync from "node:fs";
import * as os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  buildLineKey,
  findLineNumberBySnippet,
  postControllerFqcn,
  postControllerSourceFileAbs,
  startMcpClient,
  startPostAppWithAgent,
} from "@test/integrations/support/spring/social_platform/shared.fixture";

type ToolResult = {
  structuredContent?: Record<string, unknown>;
};

async function writeJson(filePath: string, payload: Record<string, unknown>): Promise<void> {
  const nextPayload =
    path.basename(filePath) === "projects.json" && Array.isArray(payload.workspaces)
      ? {
          ...payload,
          workspaces: payload.workspaces.map((workspace) => {
            if (!workspace || typeof workspace !== "object" || Array.isArray(workspace)) return workspace;
            const defaults =
              "defaults" in workspace && workspace.defaults && typeof workspace.defaults === "object"
                ? workspace.defaults
                : {};
            const orchestrator =
              "orchestrator" in defaults && defaults.orchestrator && typeof defaults.orchestrator === "object"
                ? defaults.orchestrator
                : {
                    resumePollMax: 30,
                    resumePollIntervalMs: 10000,
                    resumePollTimeoutMs: 300000,
                  };
            return {
              ...workspace,
              defaults: {
                ...defaults,
                orchestrator,
              },
            };
          }),
        }
      : payload;
  await fs.mkdir(path.dirname(filePath), { recursive: true });
  await fs.writeFile(filePath, `${JSON.stringify(nextPayload, null, 2)}\n`, "utf8");
}

async function callTool(
  mcp: Awaited<ReturnType<typeof startMcpClient>>,
  name: string,
  args: Record<string, unknown>,
): Promise<ToolResult> {
  return (await mcp.client.callTool({
    name,
    arguments: args,
  })) as ToolResult;
}

test("mcp IT: execution_orchestration accepts canonical and compatibility probe_line_hit actualPath values", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-probe-line-hit-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "probe-line-hit-project";
  const projectRootAbs = workspaceRootAbs;
  const line = await findLineNumberBySnippet(
    postControllerSourceFileAbs,
    "return postService.listPosts(author, tag, page, size);",
  );
  const strictProbeKey = buildLineKey({
    fqcn: postControllerFqcn,
    methodName: "listPosts",
    line,
  });

  let runtime: Awaited<ReturnType<typeof startPostAppWithAgent>> | undefined;
  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;

  try {
    runtime = await startPostAppWithAgent();
    const activeRuntime = runtime;
    const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
    await writeJson(probeConfigAbs, {
      defaultProfile: "dev",
      profiles: {
        dev: {
          probes: {
            "post-app": {
              baseUrl: activeRuntime.probeBaseUrl,
              include: ["com.example.social.**"],
              exclude: ["**.config.**"],
            },
          },
        },
      },
      workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
    });

    await writeJson(path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: projectRootAbs,
          executionProfiles: [
            {
              executionProfile: "canonical-run",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "probe-line-hit-canonical", onFail: "inherit" }],
            },
            {
              executionProfile: "compatibility-run",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "probe-line-hit-compatibility", onFail: "inherit" }],
            },
          ],
        },
      ],
    });

    async function writePlan(planName: string, actualPath: string): Promise<void> {
      const planRootAbs = path.join(
        workspaceRootAbs,
        ".mcpjvm",
        projectName,
        "plans",
        "regression",
        planName,
      );
      await writeJson(path.join(planRootAbs, "metadata.json"), {
        specVersion: "1.0.0",
        execution: {
          intent: "regression",
          probeVerification: true,
          pinStrictProbeKey: true,
          discoveryPolicy: "disabled",
        },
      });
      await writeJson(path.join(planRootAbs, "contract.json"), {
        targets: [
          {
            type: "class_method",
            selectors: {
              fqcn: postControllerFqcn,
              method: "listPosts",
              sourceRoot: "test/fixtures/spring-apps/social-platform/post-service/post-app",
            },
            runtimeVerification: {
              strictProbeKey,
              probeId: "post-app",
            },
          },
        ],
        prerequisites: [
          {
            key: "apiBaseUrl",
            required: true,
            secret: false,
            provisioning: "user_input",
            default: activeRuntime.apiBaseUrl,
          },
        ],
        steps: [
          {
            order: 1,
            id: "list_posts",
            targetRef: 0,
            protocol: "http",
            transport: {
              http: {
                method: "GET",
                pathTemplate: "/api/v1/posts?page=0&size=2",
              },
            },
            expect: [
              {
                id: "probe_hit",
                actualPath,
                operator: "probe_line_hit",
                expected: true,
              },
            ],
          },
        ],
      });
    }

    await writePlan("probe-line-hit-canonical", "probe.hit");
    await writePlan("probe-line-hit-compatibility", "runtime.probe.hit");

    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: activeRuntime.probeBaseUrl,
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const canonicalOut = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "canonical-run",
      },
    });
    const compatibilityOut = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "compatibility-run",
      },
    });

    assert.equal(canonicalOut.structuredContent?.resultType, "execution_orchestration");
    assert.equal(canonicalOut.structuredContent?.status, "pass");
    assert.equal(compatibilityOut.structuredContent?.resultType, "execution_orchestration");
    assert.equal(compatibilityOut.structuredContent?.status, "pass");

    async function readLatestExecutionResult(planName: string) {
      const runRootAbs = path.join(
        workspaceRootAbs,
        ".mcpjvm",
        projectName,
        "plans",
        "regression",
        planName,
        "runs",
      );
      const runIds = await fs.readdir(runRootAbs);
      assert.equal(runIds.length, 1);
      return JSON.parse(
        await fs.readFile(path.join(runRootAbs, runIds[0]!, "execution.result.json"), "utf8"),
      ) as {
        status: string;
        steps: Array<{
          status: string;
          assertions: Array<{
            actualPath: string;
            status: string;
            reasonCode: string;
            actual?: unknown;
          }>;
        }>;
      };
    }

    const canonicalExecution = await readLatestExecutionResult("probe-line-hit-canonical");
    const compatibilityExecution = await readLatestExecutionResult("probe-line-hit-compatibility");

    assert.equal(canonicalExecution.status, "pass");
    assert.equal(canonicalExecution.steps[0]?.status, "pass");
    assert.equal(canonicalExecution.steps[0]?.assertions[0]?.actualPath, "probe.hit");
    assert.equal(canonicalExecution.steps[0]?.assertions[0]?.status, "pass");
    assert.equal(canonicalExecution.steps[0]?.assertions[0]?.reasonCode, "ok");
    assert.equal(canonicalExecution.steps[0]?.assertions[0]?.actual, true);

    assert.equal(compatibilityExecution.status, "pass");
    assert.equal(compatibilityExecution.steps[0]?.status, "pass");
    assert.equal(compatibilityExecution.steps[0]?.assertions[0]?.actualPath, "runtime.probe.hit");
    assert.equal(compatibilityExecution.steps[0]?.assertions[0]?.status, "pass");
    assert.equal(compatibilityExecution.steps[0]?.assertions[0]?.reasonCode, "ok");
    assert.equal(compatibilityExecution.steps[0]?.assertions[0]?.actual, true);
  } finally {
    await mcp?.close();
    await runtime?.stop();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});
