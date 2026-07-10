import { access } from "node:fs/promises";

import type { SynthesizerFailure } from "@tools-registry/models/synthesis/synthesizer_failure.model";
import type { SynthesizerInput } from "@tools-registry/models/synthesis/synthesizer_input.model";
import type { SynthesizerOutput } from "@tools-registry/models/synthesis/synthesizer_output.model";
import { SYNTHESIZER_PLUGIN_API_VERSION } from "@tools-registry/plugin.compat";
import type { SynthesizerPlugin } from "@tools-registry/plugin.contract";

import { EXAMPLE_MARKER_FILE } from "./models/example_synthesizer.model";

async function hasExampleMarker(rootAbs: string): Promise<boolean> {
  try {
    await access(`${rootAbs}/${EXAMPLE_MARKER_FILE}`);
    return true;
  } catch {
    return false;
  }
}

function failNotReady(): SynthesizerFailure {
  return {
    status: "report",
    reasonCode: "framework_not_supported",
    failedStep: "plugin_selection",
    nextAction:
      "Example plugin is not enabled for this project. Add the marker file or replace canHandle/synthesize with real framework logic.",
    evidence: [`Missing marker file: ${EXAMPLE_MARKER_FILE}`],
    attemptedStrategies: ["example_marker_detection"],
    synthesizerUsed: "example",
  };
}

function exampleRecipe(input: SynthesizerInput): SynthesizerOutput {
  const path = "/example/health";
  return {
    status: "recipe",
    synthesizerUsed: "example",
    framework: "example",
    requestSource: "jaxrs",
    requestCandidate: {
      method: "GET",
      path,
      queryTemplate: "",
      fullUrlHint: `{{BASE_URL}}${path}`,
      rationale: [
        "Example starter recipe only.",
        `Generated for classHint=${input.classHint} methodHint=${input.methodHint}.`,
      ],
      needsConfirmation: ["Replace with real framework route synthesis before production use."],
    },
    trigger: {
      kind: "http",
      method: "GET",
      path,
      queryTemplate: "",
      fullUrlHint: `{{BASE_URL}}${path}`,
      headers: {
        Accept: "application/json",
      },
    },
    evidence: [
      `Example marker found: ${EXAMPLE_MARKER_FILE}`,
      "Recipe returned by example synthesizer scaffold.",
    ],
    attemptedStrategies: ["example_marker_detection", "example_static_recipe"],
  };
}

export const exampleSynthesizerPlugin: SynthesizerPlugin = {
  id: "example",
  framework: "example",
  pluginApiVersion: SYNTHESIZER_PLUGIN_API_VERSION,
  async canHandle(input: SynthesizerInput): Promise<boolean> {
    return hasExampleMarker(input.rootAbs);
  },
  async synthesize(input: SynthesizerInput) {
    if (!(await hasExampleMarker(input.rootAbs))) return failNotReady();
    return exampleRecipe(input);
  },
};
