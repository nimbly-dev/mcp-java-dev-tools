const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const {
  SynthesizerRegistry,
  createDefaultSynthesizerRegistry,
} = require("@tools-registry/plugin.loader");

const EXTERNAL_SYNTHESIZER_MODULES_ENV = "MCP_SYNTHESIZER_PLUGIN_MODULES";

function sampleInput() {
  return {
    rootAbs: "C:\\repo\\service",
    workspaceRootAbs: "C:\\repo",
    searchRootsAbs: ["C:\\repo\\service"],
    classHint: "HealthController",
    methodHint: "health",
    intentMode: "regression",
  };
}

async function withExternalPluginEnv(moduleSpecs: string, run: () => Promise<void>) {
  const before = process.env[EXTERNAL_SYNTHESIZER_MODULES_ENV];
  process.env[EXTERNAL_SYNTHESIZER_MODULES_ENV] = moduleSpecs;
  try {
    await run();
  } finally {
    if (before === undefined) {
      delete process.env[EXTERNAL_SYNTHESIZER_MODULES_ENV];
    } else {
      process.env[EXTERNAL_SYNTHESIZER_MODULES_ENV] = before;
    }
  }
}

test("[UT][core][plugin_loader][ok] registry selects the first plugin that can handle the input", async () => {
  const plugin = {
    id: "spring",
    framework: "spring",
    pluginApiVersion: "1.0.0",
    canHandle: async () => true,
    synthesize: async () => ({
      status: "recipe",
      synthesizerUsed: "spring",
      framework: "spring",
      requestCandidate: {
        method: "GET",
        path: "/v1/health",
        queryTemplate: "",
        fullUrlHint: "/v1/health",
        rationale: ["test"],
      },
      trigger: {
        kind: "http",
        method: "GET",
        path: "/v1/health",
        queryTemplate: "",
        fullUrlHint: "/v1/health",
        headers: {},
      },
      evidence: ["plugin_selected=spring"],
      attemptedStrategies: ["spring_annotation_mapping"],
    }),
  };

  const registry = new SynthesizerRegistry([plugin]);
  const result = await registry.synthesize(sampleInput());

  assert.equal(result.status, "recipe");
  assert.equal(result.synthesizerUsed, "spring");
});

test("[UT][core][plugin_loader][blocked_invalid] registry fails closed when no plugin can handle input", async () => {
  const registry = new SynthesizerRegistry([]);
  const result = await registry.synthesize(sampleInput());

  assert.equal(result.status, "report");
  assert.equal(result.reasonCode, "synthesizer_not_installed");
  assert.equal(result.failedStep, "plugin_selection");
});

test("[UT][core][plugin_loader][ok] registry enforces plugin API compatibility", async () => {
  assert.throws(() => {
    new SynthesizerRegistry([
      {
        id: "bad-plugin",
        framework: "spring",
        pluginApiVersion: "0.0.1",
        canHandle: async () => true,
        synthesize: async () => ({
          status: "report",
          reasonCode: "framework_not_supported",
          failedStep: "test",
          nextAction: "test",
          evidence: [],
          attemptedStrategies: [],
        }),
      },
    ]);
  }, /Incompatible synthesizer plugin/);
});

test("[UT][core][plugin_loader][ok] default registry loads external plugin modules from env", async () => {
  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-synth-plugin-"));
  const pluginModuleAbs = path.join(tmpDir, "enterprise-synth-plugin.js");
  await fs.writeFile(
    pluginModuleAbs,
    `module.exports = {
      default: {
        id: "enterprise-private",
        framework: "enterprise",
        pluginApiVersion: "1.0.0",
        async canHandle() { return true; },
        async synthesize() {
          return {
            status: "recipe",
            synthesizerUsed: "enterprise-private",
            framework: "enterprise",
            requestCandidate: {
              method: "POST",
              path: "/enterprise/probe",
              queryTemplate: "",
              fullUrlHint: "/enterprise/probe",
              rationale: ["external_plugin_selected=true"]
            },
            trigger: {
              kind: "http",
              method: "POST",
              path: "/enterprise/probe",
              queryTemplate: "",
              fullUrlHint: "/enterprise/probe",
              headers: {}
            },
            evidence: ["external_plugin_selected=true"],
            attemptedStrategies: ["external_enterprise_plugin"]
          };
        }
      }
    };`,
    "utf8",
  );

  await withExternalPluginEnv(pluginModuleAbs, async () => {
    const registry = createDefaultSynthesizerRegistry();
    const result = await registry.synthesize(sampleInput());
    assert.equal(result.status, "recipe");
    assert.equal(result.synthesizerUsed, "enterprise-private");
  });
});

test("[UT][core][plugin_loader][blocked_invalid] default registry fails closed when external plugin module path is invalid", async () => {
  await withExternalPluginEnv("C:\\missing\\enterprise-synth.js", async () => {
    const registry = createDefaultSynthesizerRegistry();
    const result = await registry.synthesize(sampleInput());
    assert.equal(result.status, "report");
    assert.equal(result.reasonCode, "synthesizer_not_installed");
    assert.equal(result.failedStep, "plugin_bootstrap");
    assert.equal(result.attemptedStrategies[0], "registry_plugin_bootstrap");
    assert.ok(result.evidence.some((v: string) => v.includes("plugin_module_load_failed")));
  });
});

test("[UT][core][plugin_loader][blocked_invalid] default registry fails closed when external plugin is API-incompatible", async () => {
  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-synth-plugin-"));
  const pluginModuleAbs = path.join(tmpDir, "bad-enterprise-synth-plugin.js");
  await fs.writeFile(
    pluginModuleAbs,
    `module.exports = {
      plugin: {
        id: "enterprise-bad",
        framework: "enterprise",
        pluginApiVersion: "0.0.1",
        async canHandle() { return true; },
        async synthesize() {
          return {
            status: "report",
            reasonCode: "framework_not_supported",
            failedStep: "compat",
            nextAction: "noop",
            evidence: [],
            attemptedStrategies: []
          };
        }
      }
    };`,
    "utf8",
  );

  await withExternalPluginEnv(pluginModuleAbs, async () => {
    const registry = createDefaultSynthesizerRegistry();
    const result = await registry.synthesize(sampleInput());
    assert.equal(result.status, "report");
    assert.equal(result.reasonCode, "synthesizer_not_installed");
    assert.equal(result.failedStep, "plugin_bootstrap");
    assert.ok(result.evidence.some((v: string) => v.includes("plugin_incompatible")));
  });
});
