import { promises as fs } from "node:fs";
import path from "node:path";

import type { SynthesisRecipeCandidate } from "@/models/synthesis/synthesizer_output.model";

type GatewayRouteConfigSuccess = {
  status: "ok";
  requestCandidate: SynthesisRecipeCandidate;
  evidence: string[];
  attemptedStrategies: string[];
};

type GatewayRouteConfigFailure = {
  status: "report";
  reasonCode: "spring_gateway_route_not_found" | "spring_gateway_route_ambiguous";
  failedStep: "gateway_route_config_resolution";
  nextAction: string;
  evidence: string[];
  attemptedStrategies: string[];
};

export type GatewayRouteConfigResolveResult = GatewayRouteConfigSuccess | GatewayRouteConfigFailure;

const GATEWAY_ROUTE_ATTEMPTED_STRATEGY = "spring_gateway_route_config";

function normalizePath(pathValue: string): string {
  const trimmed = pathValue.trim();
  if (!trimmed) return "/";
  return trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
}

function stripQuoted(value: string): string {
  const trimmed = value.trim();
  if (
    (trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
    (trimmed.startsWith("'") && trimmed.endsWith("'"))
  ) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
}

function extractYamlPathPredicates(input: string): string[] {
  const out: string[] = [];
  for (const rawLine of input.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const match = line.match(/^-?\s*Path\s*=\s*(.+)$/i);
    if (!match) continue;
    const rhs = stripQuoted(match[1] ?? "");
    for (const token of rhs.split(",")) {
      const normalized = normalizePath(token);
      if (normalized.length > 0) out.push(normalized);
    }
  }
  return out;
}

function extractPropertiesPathPredicates(input: string): string[] {
  const out: string[] = [];
  for (const rawLine of input.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const match = line.match(/^spring\.cloud\.gateway\.routes\[\d+\]\.predicates\[\d+\]\s*=\s*Path\s*=\s*(.+)$/i);
    if (!match) continue;
    const rhs = stripQuoted(match[1] ?? "");
    for (const token of rhs.split(",")) {
      const normalized = normalizePath(token);
      if (normalized.length > 0) out.push(normalized);
    }
  }
  return out;
}

function unique(values: string[]): string[] {
  return values.filter((value, index, arr) => arr.indexOf(value) === index);
}

function bySpecificityDesc(left: string, right: string): number {
  const score = (value: string): number => {
    const wildcardPenalty = (value.match(/\*/g) ?? []).length * 10;
    return value.length - wildcardPenalty;
  };
  const diff = score(right) - score(left);
  if (diff !== 0) return diff;
  return left.localeCompare(right);
}

async function readIfExists(fileAbs: string): Promise<string | null> {
  try {
    return await fs.readFile(fileAbs, "utf8");
  } catch {
    return null;
  }
}

function failure(args: {
  reasonCode: "spring_gateway_route_not_found" | "spring_gateway_route_ambiguous";
  nextAction: string;
  evidence: string[];
}): GatewayRouteConfigFailure {
  return {
    status: "report",
    reasonCode: args.reasonCode,
    failedStep: "gateway_route_config_resolution",
    nextAction: args.nextAction,
    evidence: args.evidence,
    attemptedStrategies: [GATEWAY_ROUTE_ATTEMPTED_STRATEGY],
  };
}

export async function resolveGatewayRouteConfig(args: {
  projectRootAbs: string;
}): Promise<GatewayRouteConfigResolveResult> {
  const candidates = [
    path.join(args.projectRootAbs, "src", "main", "resources", "application.yml"),
    path.join(args.projectRootAbs, "src", "main", "resources", "application.yaml"),
    path.join(args.projectRootAbs, "src", "main", "resources", "application.properties"),
  ];

  const found: string[] = [];
  const discoveredPaths: string[] = [];

  for (const fileAbs of candidates) {
    const text = await readIfExists(fileAbs);
    if (!text) continue;
    found.push(fileAbs);
    const paths = fileAbs.endsWith(".properties")
      ? extractPropertiesPathPredicates(text)
      : extractYamlPathPredicates(text);
    for (const pathToken of paths) discoveredPaths.push(pathToken);
  }

  const uniquePaths = unique(discoveredPaths);
  if (uniquePaths.length === 0) {
    return failure({
      reasonCode: "spring_gateway_route_not_found",
      nextAction:
        "No Spring Cloud Gateway Path predicates were found in application config. Provide explicit request context or target a routable controller method and rerun route_synthesis with action=create_recipe.",
      evidence: [
        `projectRootAbs=${args.projectRootAbs}`,
        `scannedConfigFiles=${found.length > 0 ? found.join("|") : "(none)"}`,
      ],
    });
  }

  const ranked = [...uniquePaths].sort(bySpecificityDesc);
  const top = ranked[0] ?? "";
  const tied = ranked.filter((candidate) => bySpecificityDesc(candidate, top) === 0);
  if (!top) {
    return failure({
      reasonCode: "spring_gateway_route_not_found",
      nextAction:
        "No usable Spring Cloud Gateway Path predicate was resolved. Provide explicit request context and rerun route_synthesis with action=create_recipe.",
      evidence: [`projectRootAbs=${args.projectRootAbs}`],
    });
  }
  if (tied.length > 1) {
    return failure({
      reasonCode: "spring_gateway_route_ambiguous",
      nextAction:
        "Multiple gateway Path predicates are equally specific. Provide explicit request path/method context and rerun route_synthesis with action=create_recipe.",
      evidence: [
        `projectRootAbs=${args.projectRootAbs}`,
        `ambiguousPaths=${tied.join("|")}`,
      ],
    });
  }

  return {
    status: "ok",
    requestCandidate: {
      method: "GET",
      path: top,
      queryTemplate: "",
      fullUrlHint: top,
      rationale: [
        "Resolved request path from Spring Cloud Gateway route Path predicate.",
        `Config source: ${found[0] ?? "(unknown)"}`,
      ],
    },
    evidence: [
      "mapping_source=spring_gateway_route_config",
      `gateway_path=${top}`,
      `scannedConfigFiles=${found.join("|")}`,
    ],
    attemptedStrategies: [GATEWAY_ROUTE_ATTEMPTED_STRATEGY],
  };
}

