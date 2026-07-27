import {
  assert,
  fs,
  fssync,
  http,
  os,
  path,
  test,
  callTool,
  writeJson,
  listen,
  renderPerformanceResultFromArtifacts,
  startMcpClient,
} from "./execution_orchestration.shared";

test("[IT][execution_orchestration][execute] execution_orchestration classifies outer timeout exhaustion deterministically after resumed plan progress", async () => {
  const tmpRoot = await fs.mkdtemp(
    path.join(os.tmpdir(), "mcp-execution-orchestration-timeout-budget-it-"),
  );
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-timeout";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  let requestCount = 0;

  const appServer = http.createServer(async (req, res) => {
    if (req.method === "GET" && (req.url === "/a" || req.url === "/b")) {
      requestCount += 1;
      await new Promise((resolve) => setTimeout(resolve, 15));
      res.statusCode = 200;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ ok: true, path: req.url }));
      return;
    }
    res.statusCode = 404;
    res.end(JSON.stringify({ reason: "missing" }));
  });
  const appPort = await listen(appServer);

  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        probes: {
          "gateway-service": {
            baseUrl: "http://127.0.0.1:9196",
            include: ["com.example.**"],
            exclude: [],
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
        defaults: {
          requestTimeoutMs: 100,
          retryMax: 2,
          orchestrator: {
            resumePollMax: 5,
            resumePollIntervalMs: 5,
            resumePollTimeoutMs: 10,
          },
        },
        executionProfiles: [
          {
            executionProfile: "timeout-budget-run",
            executionPolicy: "stop_on_fail",
            plans: [
              { order: 1, planName: "plan-a", onFail: "inherit" },
              { order: 2, planName: "plan-b", onFail: "inherit" },
            ],
          },
        ],
      },
    ],
  });

  for (const [planName, routePath] of [
    ["plan-a", "/a"],
    ["plan-b", "/b"],
  ] as const) {
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
        probeVerification: false,
        pinStrictProbeKey: false,
        discoveryPolicy: "allow_discoverable_prerequisites",
      },
    });
    await writeJson(path.join(planRootAbs, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [
        {
          key: "apiBaseUrl",
          required: true,
          secret: false,
          provisioning: "user_input",
          default: `http://127.0.0.1:${appPort}`,
        },
      ],
      steps: [
        {
          order: 1,
          id: `${planName}_step`,
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", pathTemplate: routePath } },
          expect: [
            {
              id: "http_ok",
              actualPath: "response.statusCode",
              operator: "numeric_gte",
              expected: 200,
            },
          ],
        },
      ],
    });
  }

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const out = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "timeout-budget-run",
      },
    });

    assert.equal(out.structuredContent?.status, "blocked");
    assert.equal(out.structuredContent?.reasonCode, "orchestrator_timeout_budget_exhausted");
    assert.equal(out.structuredContent?.completedPlanCount, 1);
    assert.equal(
      (out.structuredContent?.progressSummary as Record<string, unknown>)?.progressState,
      "terminal",
    );
    assert.equal(requestCount, 1);
  } finally {
    appServer.close();
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});
