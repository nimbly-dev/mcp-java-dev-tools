const test = require("node:test");
const assert = require("node:assert/strict");

const { resolveDynamicAttachLifecycle } = require("@tools-feature-execution-orchestration");

function dynamicContext(name: string) {
  return {
    name,
    mode: "terminal",
    autoStart: true,
    sidecarLifecycle: {
      activation: "dynamic_attach_local",
      targetStartupName: `${name}-startup`,
      probeId: name,
      verifyProbeAfterAttach: true,
    },
    startups: [
      {
        name: `${name}-startup`,
        command: "java",
        args: ["-jar", `target/${name}.jar`],
      },
    ],
  };
}

test("[UT][execution-orchestration][dynamic-attach] resolves the project-declared lifecycle startup", () => {
  const result = resolveDynamicAttachLifecycle({
    workspace: { runtimeContexts: [dynamicContext("orders")] },
    profile: {
      runtimeContextName: "orders",
      plans: [{ order: 1, planName: "orders-regression" }],
    },
  });

  assert.equal(result.ok, true);
  if (result.ok) {
    assert.equal(result.selection?.runtimeContext.name, "orders");
    assert.equal(result.selection?.startup.command, "java");
  }
});

test("[UT][execution-orchestration][dynamic-attach] fails closed when an unnamed profile has multiple dynamic contexts", () => {
  const result = resolveDynamicAttachLifecycle({
    workspace: { runtimeContexts: [dynamicContext("orders"), dynamicContext("payments")] },
    profile: {
      plans: [{ order: 1, planName: "combined-regression" }],
    },
  });

  assert.equal(result.ok, false);
  if (!result.ok) assert.equal(result.reasonCode, "dynamic_attach_runtime_context_ambiguous");
});
