import type { SynthesizerFailure } from "@tools-registry/models/synthesis/synthesizer_failure.model";
import type { SynthesizerInput } from "@tools-registry/models/synthesis/synthesizer_input.model";
import type { SynthesizerOutput } from "@tools-registry/models/synthesis/synthesizer_output.model";

export type SynthesizerResult = SynthesizerOutput | SynthesizerFailure;

export interface SynthesizerPlugin {
  id: string;
  framework: string;
  pluginApiVersion: string;
  canHandle(input: SynthesizerInput): Promise<boolean>;
  synthesize(input: SynthesizerInput): Promise<SynthesizerResult>;
}
