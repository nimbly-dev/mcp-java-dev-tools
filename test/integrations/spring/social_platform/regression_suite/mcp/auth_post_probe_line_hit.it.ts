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
                    resumePollIntervalMs: 10_000,
                    resumePollTimeoutMs: 300_000,
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

test("mcp IT: execution_orchestration preserves probe.hit for authenticated post trigger with strict controller probe verification", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-auth-post-probe-line-hit-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "auth-post-probe-project";
  const planName = "auth-post-probe-plan";
  const projectRootAbs = workspaceRootAbs;
  const line = await findLineNumberBySnippet(
    postControllerSourceFileAbs,
    "return postService.createPost(request, authentication.getName());",
  );
  const strictProbeKey = buildLineKey({
    fqcn: postControllerFqcn,
    methodName: "createPost",
    line,
  });

  let runtime: Awaited<ReturnType<typeof startPostAppWithAgent>> | undefined;
  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;

  try {
    runtime = await startPostAppWithAgent();
    const activeRuntime = runtime;
    const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
    const planRootAbs = path.join(
      workspaceRootAbs,
      ".mcpjvm",
      projectName,
      "plans",
      "regression",
      planName,
    );
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
              executionProfile: "auth-post-run",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName, onFail: "inherit" }],
            },
          ],
        },
      ],
    });

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
            method: "createPost",
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
          id: "create_post",
          targetRef: 0,
          protocol: "http",
          transport: {
            http: {
              method: "POST",
              pathTemplate: "/api/v1/posts",
              headers: {
                Authorization: "Bearer alice-token",
              },
              body: {
                content: "Probe verified authenticated post body.",
                visibility: "PUBLIC",
                tags: ["java", "probe"],
              },
            },
          },
          expect: [
            {
              id: "http_created",
              actualPath: "response.statusCode",
              operator: "field_equals",
              expected: 201,
            },
            {
              id: "probe_hit",
              actualPath: "probe.hit",
              operator: "probe_line_hit",
              expected: true,
            },
          ],
        },
      ],
    });

    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: activeRuntime.probeBaseUrl,
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const out = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "auth-post-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");

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
    const executionResult = JSON.parse(
      await fs.readFile(path.join(runRootAbs, runIds[0]!, "execution.result.json"), "utf8"),
    ) as {
      status: string;
      steps: Array<{
        status: string;
        statusCode: number;
        assertions: Array<{
          actualPath: string;
          status: string;
          actual?: unknown;
          expected?: unknown;
        }>;
      }>;
    };

    assert.equal(executionResult.status, "pass");
    assert.equal(out.structuredContent?.status, "pass");
    assert.equal(executionResult.steps[0]?.status, "pass");
    assert.equal(executionResult.steps[0]?.statusCode, 201);
    assert.equal(executionResult.steps[0]?.assertions[1]?.actualPath, "probe.hit");
    assert.equal(executionResult.steps[0]?.assertions[1]?.status, "pass");
    assert.equal(executionResult.steps[0]?.assertions[1]?.actual, true);
    assert.equal(executionResult.steps[0]?.assertions[1]?.expected, true);
  } finally {
    await mcp?.close();
    await runtime?.stop();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});
