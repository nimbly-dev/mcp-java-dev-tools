import * as path from "node:path";

import type { SynthesizerInput } from "@tools-registry/models/synthesis/synthesizer_input.model";
import type { SynthesizerFailure } from "@tools-registry/models/synthesis/synthesizer_failure.model";
import { springSynthesizerPlugin } from "@tools-spring-http/plugin";
import { assertPluginCompatibility } from "@tools-registry/plugin.compat";
import type { SynthesizerPlugin, SynthesizerResult } from "@tools-registry/plugin.contract";

const EXTERNAL_SYNTHESIZER_MODULES_ENV = "MCP_SYNTHESIZER_PLUGIN_MODULES";

type RegistryBootstrap = {
  bootstrapFailure?: SynthesizerFailure;
};

function isRecord(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === "object";
}

function isSynthesizerPluginLike(v: unknown): v is SynthesizerPlugin {
  if (!isRecord(v)) return false;
  return (
    typeof v.id === "string" &&
    typeof v.framework === "string" &&
    typeof v.pluginApiVersion === "string" &&
    typeof v.canHandle === "function" &&
    typeof v.synthesize === "function"
  );
}

function parseModuleSpecs(raw: string): string[] {
  const splitter = path.delimiter === ";" ? /[;,\r\n]+/ : /[:,\r\n]+/;
  return raw
    .split(splitter)
    .map((v) => v.trim())
    .filter((v) => v.length > 0);
}

function resolveModuleSpecifier(spec: string): string {
  if (path.isAbsolute(spec)) {
    return spec;
  }
  if (spec.startsWith(".")) {
    return path.resolve(process.cwd(), spec);
  }
  return spec;
}

function collectPluginCandidates(moduleValue: unknown): SynthesizerPlugin[] {
  const candidates: unknown[] = [];
  const visited = new Set<unknown>();
  const push = (v: unknown) => {
    if (v === undefined || v === null) return;
    if (visited.has(v)) return;
    visited.add(v);
    candidates.push(v);
  };

  push(moduleValue);
  if (isRecord(moduleValue)) {
    push(moduleValue.default);
    push(moduleValue.synthesizerPlugin);
    push(moduleValue.plugin);
    const namedPlugins = moduleValue.plugins;
    if (Array.isArray(namedPlugins)) {
      namedPlugins.forEach(push);
    }
  }

  return candidates.filter(isSynthesizerPluginLike);
}

function loadExternalPluginsFromEnv(): {
  plugins: SynthesizerPlugin[];
  configuredSpecs: string[];
  diagnostics: string[];
  failures: string[];
} {
  const configuredRaw = process.env[EXTERNAL_SYNTHESIZER_MODULES_ENV]?.trim();
  if (!configuredRaw) {
    return { plugins: [], configuredSpecs: [], diagnostics: [], failures: [] };
  }

  const configuredSpecs = parseModuleSpecs(configuredRaw);
  const plugins: SynthesizerPlugin[] = [];
  const diagnostics: string[] = [];
  const failures: string[] = [];

  for (const spec of configuredSpecs) {
    const resolvedSpecifier = resolveModuleSpecifier(spec);
    let loadedModule: unknown;
    try {
      loadedModule = require(resolvedSpecifier);
    } catch (err) {
      failures.push(
        `plugin_module_load_failed spec='${spec}' resolved='${resolvedSpecifier}' error='${
          err instanceof Error ? err.message : String(err)
        }'`,
      );
      continue;
    }

    const discovered = collectPluginCandidates(loadedModule);
    if (discovered.length === 0) {
      failures.push(
        `plugin_contract_not_found spec='${spec}' resolved='${resolvedSpecifier}' expected_export='SynthesizerPlugin or default/plugin/synthesizerPlugin/plugins[]'`,
      );
      continue;
    }

    let compatibilityError: string | undefined;
    for (const plugin of discovered) {
      try {
        assertPluginCompatibility(plugin);
      } catch (err) {
        compatibilityError = err instanceof Error ? err.message : String(err);
        break;
      }
    }
    if (compatibilityError) {
      failures.push(
        `plugin_incompatible spec='${spec}' resolved='${resolvedSpecifier}' error='${compatibilityError}'`,
      );
      continue;
    }

    diagnostics.push(
      `plugin_module_loaded spec='${spec}' resolved='${resolvedSpecifier}' plugins=${discovered
        .map((p) => p.id)
        .join("|")}`,
    );
    plugins.push(...discovered);
  }

  return { plugins, configuredSpecs, diagnostics, failures };
}

export class SynthesizerRegistry {
  private readonly plugins: SynthesizerPlugin[];
  private readonly bootstrapFailure: SynthesizerFailure | undefined;

  constructor(plugins: SynthesizerPlugin[], bootstrap?: RegistryBootstrap) {
    plugins.forEach(assertPluginCompatibility);
    this.plugins = plugins;
    this.bootstrapFailure = bootstrap?.bootstrapFailure;
  }

  listCapabilities(): Array<{ id: string; framework: string; pluginApiVersion: string }> {
    return this.plugins.map((plugin) => ({
      id: plugin.id,
      framework: plugin.framework,
      pluginApiVersion: plugin.pluginApiVersion,
    }));
  }

  async synthesize(input: SynthesizerInput): Promise<SynthesizerResult> {
    if (this.bootstrapFailure) {
      return this.bootstrapFailure;
    }
    for (const plugin of this.plugins) {
      if (!(await plugin.canHandle(input))) continue;
      return plugin.synthesize(input);
    }
    const out: SynthesizerFailure = {
      status: "report",
      reasonCode: "synthesizer_not_installed",
      failedStep: "plugin_selection",
      nextAction:
        "No compatible synthesizer plugin is installed for this project. Install the spring synthesizer pack or provide a supported framework plugin.",
      evidence: ["No registered synthesizer returned canHandle=true."],
      attemptedStrategies: ["registry_plugin_selection"],
    };
    return out;
  }
}

export function createDefaultSynthesizerRegistry(): SynthesizerRegistry {
  const builtIns: SynthesizerPlugin[] = [springSynthesizerPlugin];
  const external = loadExternalPluginsFromEnv();
  if (external.configuredSpecs.length === 0) {
    return new SynthesizerRegistry(builtIns);
  }

  const merged = [...external.plugins, ...builtIns];
  if (external.failures.length > 0) {
    return new SynthesizerRegistry(merged, {
      bootstrapFailure: {
        status: "report",
        reasonCode: "synthesizer_not_installed",
        failedStep: "plugin_bootstrap",
        nextAction:
          `Fix ${EXTERNAL_SYNTHESIZER_MODULES_ENV} so every configured plugin module can be loaded and validated, then rerun.`,
        evidence: [
          `configuredModules=${external.configuredSpecs.join("|")}`,
          ...external.diagnostics,
          ...external.failures,
        ],
        attemptedStrategies: ["registry_plugin_bootstrap"],
      },
    });
  }

  return new SynthesizerRegistry(merged);
}
