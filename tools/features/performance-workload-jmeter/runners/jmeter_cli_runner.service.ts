import { spawn } from "node:child_process";
import path from "node:path";
import { promises as fs } from "node:fs";

import { collectJmeterJtlMetrics } from "@tools-performance-workload-jmeter/collectors/jmeter_jtl_result.collector";
import { resolveJmeterExecutable } from "@tools-performance-workload-jmeter/loaders/jmeter_installation.loader";
import type {
  JmeterGeneratedHttpLoadModel,
  JmeterGeneratedHttpRequest,
  JmeterWorkloadProvider,
  JmeterWorkloadRunResult,
} from "@tools-performance-workload-jmeter/models/jmeter_workload_provider.model";
import { renderGeneratedHttpJmx } from "@tools-performance-workload-jmeter/renderers/jmeter_generated_http_jmx.renderer";

async function fileExists(absPath: string): Promise<boolean> {
  try {
    await fs.access(absPath);
    return true;
  } catch {
    return false;
  }
}

export async function runJmeterGeneratedHttpWorkload(args: {
  provider: JmeterWorkloadProvider;
  request: JmeterGeneratedHttpRequest;
  loadModel: JmeterGeneratedHttpLoadModel;
  runDirAbs: string;
  planName: string;
}): Promise<JmeterWorkloadRunResult> {
  const executable = await resolveJmeterExecutable(
    typeof args.provider.options?.installationPath === "string"
      ? {
          installationPath: args.provider.options.installationPath,
        }
      : {},
  );
  const emitJmx = args.provider.options?.emitJmx !== false;
  const emitJtl = args.provider.options?.emitJtl !== false;
  const emitLog = args.provider.options?.emitLog !== false;
  const jmxPathAbs = path.join(args.runDirAbs, "workload.jmeter.jmx");
  const jtlPathAbs = path.join(args.runDirAbs, "workload.jmeter.jtl");
  const logPathAbs = path.join(args.runDirAbs, "workload.jmeter.log");

  if (!executable) {
    return {
      status: "blocked",
      reasonCode: "performance_jmeter_missing",
      requiredUserAction: [
        "Install Apache JMeter locally or set workloadProvider.options.installationPath / MCP_JAVA_DEV_TOOLS_JMETER_HOME.",
      ],
      artifacts: {
        ...(emitJmx ? { jmxPathAbs } : {}),
        ...(emitJtl ? { jtlPathAbs } : {}),
        ...(emitLog ? { logPathAbs } : {}),
      },
    };
  }

  if (emitJmx) {
    await fs.writeFile(
      jmxPathAbs,
      renderGeneratedHttpJmx({
        request: args.request,
        loadModel: args.loadModel,
        planName: args.planName,
      }),
      "utf8",
    );
  }

  const argsList = [
    "-n",
    "-t",
    jmxPathAbs,
    "-l",
    jtlPathAbs,
    "-j",
    logPathAbs,
    "-Jjmeter.save.saveservice.output_format=csv",
    "-Jjmeter.save.saveservice.print_field_names=true",
    "-Jjmeter.save.saveservice.timestamp_format=ms",
    "-Jjmeter.save.saveservice.time=true",
    "-Jjmeter.save.saveservice.timestamp=true",
    "-Jjmeter.save.saveservice.success=true",
    "-Jjmeter.save.saveservice.label=true",
    "-Jjmeter.save.saveservice.response_code=true",
    "-Jjmeter.save.saveservice.response_message=true",
    "-Jjmeter.save.saveservice.thread_name=true",
    "-Jjmeter.save.saveservice.url=true",
  ];

  const exitCode = await new Promise<number>((resolve) => {
    const child = spawn(executable, argsList, {
      cwd: args.runDirAbs,
      env: process.env,
      windowsHide: true,
      shell: process.platform === "win32",
    });
    let stderr = "";
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString();
    });
    child.on("error", () => resolve(-1));
    child.on("close", async (code) => {
      if (stderr.trim().length > 0 && !(await fileExists(logPathAbs))) {
        await fs.writeFile(logPathAbs, stderr, "utf8").catch(() => undefined);
      }
      resolve(typeof code === "number" ? code : -1);
    });
  });

  if (exitCode !== 0) {
    return {
      status: "blocked",
      reasonCode: "performance_jmeter_execution_failed",
      requiredUserAction: ["Inspect workload.jmeter.log and workload.jmeter.jmx, then rerun the performance suite."],
      artifacts: {
        ...(emitJmx ? { jmxPathAbs } : {}),
        ...(emitJtl ? { jtlPathAbs } : {}),
        ...(emitLog ? { logPathAbs } : {}),
      },
    };
  }

  const metrics = (await fileExists(jtlPathAbs)) ? await collectJmeterJtlMetrics({ jtlPathAbs }) : null;
  if (!metrics) {
    return {
      status: "blocked",
      reasonCode: "performance_jmeter_results_missing",
      requiredUserAction: ["Ensure the JMeter CLI writes a readable CSV JTL result file and rerun."],
      artifacts: {
        ...(emitJmx ? { jmxPathAbs } : {}),
        ...(emitJtl ? { jtlPathAbs } : {}),
        ...(emitLog ? { logPathAbs } : {}),
      },
    };
  }

  return {
    status: "completed",
    metrics,
    artifacts: {
      ...(emitJmx ? { jmxPathAbs } : {}),
      ...(emitJtl ? { jtlPathAbs } : {}),
      ...(emitLog ? { logPathAbs } : {}),
    },
  };
}
