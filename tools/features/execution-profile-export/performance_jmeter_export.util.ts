import { promises as fs } from "node:fs";
import path from "node:path";

import { renderGeneratedHttpJmx } from "@tools-performance-workload-jmeter/renderers/jmeter_generated_http_jmx.renderer";

import type { PerformanceExportPlanContract } from "@tools-export-execution-profile/loaders/performance_plan_contract.loader";

export type PerformanceExportBundlePlan = {
  order: number;
  planName: string;
  providedContext?: Record<string, unknown>;
  probeBaseUrl?: string;
  contract: PerformanceExportPlanContract;
  exportedArtifacts?: {
    jmxPathRel?: string;
  };
};

function joinRequestUrl(entrypoint: PerformanceExportPlanContract["entrypoints"][number]): string {
  const baseUrl = String(entrypoint.transport.baseUrl).replace(/\/$/, "");
  const requestPath = String(entrypoint.request.path);
  const pathWithSlash = requestPath.startsWith("/") ? requestPath : `/${requestPath}`;
  const queryTemplate = entrypoint.request.queryTemplate;
  if (!queryTemplate || Object.keys(queryTemplate).length === 0) {
    return `${baseUrl}${pathWithSlash}`;
  }
  const params = new URLSearchParams();
  for (const [key, raw] of Object.entries(queryTemplate)) {
    if (typeof raw === "undefined" || raw === null) {
      continue;
    }
    if (typeof raw === "string" || typeof raw === "number" || typeof raw === "boolean") {
      params.set(key, String(raw));
      continue;
    }
    params.set(key, JSON.stringify(raw));
  }
  const query = params.toString();
  return query.length > 0 ? `${baseUrl}${pathWithSlash}?${query}` : `${baseUrl}${pathWithSlash}`;
}

export async function emitPerformanceExportJmeterArtifacts(args: {
  exportDirAbs: string;
  bundlePlans: PerformanceExportBundlePlan[];
}): Promise<PerformanceExportBundlePlan[]> {
  const nextPlans: PerformanceExportBundlePlan[] = [];
  for (const plan of args.bundlePlans) {
    if (plan.contract.workloadProvider.type !== "jmeter") {
      nextPlans.push(plan);
      continue;
    }
    const jmeterDirAbs = path.join(args.exportDirAbs, "artifacts", "jmeter");
    await fs.mkdir(jmeterDirAbs, { recursive: true });
    const jmxFileName = `${plan.planName}.workload.jmeter.jmx`;
    const jmxPathAbs = path.join(jmeterDirAbs, jmxFileName);
    const entrypoint = plan.contract.entrypoints[0];
    if (!entrypoint) {
      nextPlans.push(plan);
      continue;
    }
    const headers = {
      ...(entrypoint.transport.defaultHeaders ?? {}),
      ...(entrypoint.request.headers ?? {}),
    };
    await fs.writeFile(
      jmxPathAbs,
      renderGeneratedHttpJmx({
        request: {
          method: entrypoint.request.method,
          url: joinRequestUrl(entrypoint),
          ...(Object.keys(headers).length > 0 ? { headers } : {}),
          ...("body" in entrypoint.request ? { body: entrypoint.request.body } : {}),
        },
        loadModel: plan.contract.loadModel,
        planName: plan.planName,
      }),
      "utf8",
    );
    nextPlans.push({
      ...plan,
      exportedArtifacts: {
        ...(plan.exportedArtifacts ?? {}),
        jmxPathRel: path.join("artifacts", "jmeter", jmxFileName).replace(/\\/g, "/"),
      },
    });
  }
  return nextPlans;
}
