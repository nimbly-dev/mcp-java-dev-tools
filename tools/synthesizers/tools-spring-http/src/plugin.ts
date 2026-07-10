import type { SynthesizerInput } from "@tools-registry/models/synthesis/synthesizer_input.model";
import { isSpringProject } from "@tools-spring-http/detect.util";
import { synthesizeSpringRecipe } from "@tools-spring-http/synthesis.util";
import { SYNTHESIZER_PLUGIN_API_VERSION } from "@tools-registry/plugin.compat";
import type { SynthesizerPlugin } from "@tools-registry/plugin.contract";

export const springSynthesizerPlugin: SynthesizerPlugin = {
  id: "spring",
  framework: "spring",
  pluginApiVersion: SYNTHESIZER_PLUGIN_API_VERSION,
  async canHandle(input: SynthesizerInput): Promise<boolean> {
    const options = {
      searchRootsAbs: input.searchRootsAbs,
      ...(input.inferredTargetFileAbs
        ? { inferredTargetFileAbs: input.inferredTargetFileAbs }
        : {}),
    };
    return isSpringProject(input.rootAbs, options);
  },
  async synthesize(input: SynthesizerInput) {
    return synthesizeSpringRecipe(input);
  },
};
