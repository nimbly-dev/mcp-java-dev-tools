export type RouteSynthesisRecipeInput = {
  projectRootAbs: string;
  classHint: string;
  methodHint: string;
  lineHint?: number;
  mappingsBaseUrl?: string;
  discoveryPreference?: "static_only" | "runtime_first" | "runtime_only";
  additionalSourceRoots?: string[];
  apiBasePath?: string;
  intentMode: "line_probe" | "regression";
  authToken?: string;
  authUsername?: string;
  authPassword?: string;
  actuationEnabled?: boolean;
  actuationReturnBoolean?: boolean;
  actuationActuatorId?: string;
  outputTemplate?: string;
  probeId?: string;
  probeBaseUrl?: string;
};

export type RouteSynthesisRecipeInputHints = {
  classHint: string | undefined;
  methodHint: string | undefined;
  lineHint: number | undefined;
  mappingsBaseUrl: string | undefined;
  discoveryPreference: "static_only" | "runtime_first" | "runtime_only" | undefined;
  additionalSourceRoots: string[] | undefined;
  apiBasePath: string | undefined;
  actuationEnabled: boolean | undefined;
  actuationReturnBoolean: boolean | undefined;
  actuationActuatorId: string | undefined;
  probeId: string | undefined;
  probeBaseUrl: string | undefined;
};

export function normalizeRecipeCreateInput(input: Record<string, unknown>): {
  inputRecord: RouteSynthesisRecipeInput;
  inputHints: RouteSynthesisRecipeInputHints;
} {
  const inputRecord = input as RouteSynthesisRecipeInput;
  return {
    inputRecord,
    inputHints: {
      classHint: typeof inputRecord.classHint === "string" ? inputRecord.classHint : undefined,
      methodHint: typeof inputRecord.methodHint === "string" ? inputRecord.methodHint : undefined,
      lineHint: typeof inputRecord.lineHint === "number" ? inputRecord.lineHint : undefined,
      mappingsBaseUrl:
        typeof inputRecord.mappingsBaseUrl === "string" ? inputRecord.mappingsBaseUrl : undefined,
      discoveryPreference:
        inputRecord.discoveryPreference === "static_only" ||
        inputRecord.discoveryPreference === "runtime_first" ||
        inputRecord.discoveryPreference === "runtime_only"
          ? inputRecord.discoveryPreference
          : undefined,
      additionalSourceRoots:
        Array.isArray(inputRecord.additionalSourceRoots) &&
        inputRecord.additionalSourceRoots.every((value) => typeof value === "string")
          ? inputRecord.additionalSourceRoots
          : undefined,
      apiBasePath:
        typeof inputRecord.apiBasePath === "string" ? inputRecord.apiBasePath : undefined,
      actuationEnabled:
        typeof inputRecord.actuationEnabled === "boolean"
          ? inputRecord.actuationEnabled
          : undefined,
      actuationReturnBoolean:
        typeof inputRecord.actuationReturnBoolean === "boolean"
          ? inputRecord.actuationReturnBoolean
          : undefined,
      actuationActuatorId:
        typeof inputRecord.actuationActuatorId === "string"
          ? inputRecord.actuationActuatorId
          : undefined,
      probeId: typeof inputRecord.probeId === "string" ? inputRecord.probeId : undefined,
      probeBaseUrl:
        typeof inputRecord.probeBaseUrl === "string" ? inputRecord.probeBaseUrl : undefined,
    },
  };
}
