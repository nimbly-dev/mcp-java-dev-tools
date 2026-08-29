package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import java.util.Map;

/** Appends HTTP workload requests to an exported POSIX shell replay. */
final class ExecutionExportShellWorkload {

    void append(StringBuilder script, ExecutionExportWorkload.Workload workload) {
        for (ExecutionExportWorkload.PlanWorkload plan : workload.plans()) {
            script.append("echo ").append(ExecutionExportShellQuoting.shellSingleQuoted(
                    "[" + plan.suiteType() + ":" + plan.planName() + "] replay workload")).append("\n");
            for (ExecutionExportWorkload.WorkloadRequest request : plan.requests()) {
                script.append("__step_url=\"$(resolve_mcpjvm_template ")
                        .append(ExecutionExportShellQuoting.shellSingleQuoted(request.url()))
                        .append(")\"\n");
                if (request.body() != null) {
                    script.append("__step_body=\"$(resolve_mcpjvm_template ")
                            .append(ExecutionExportShellQuoting.shellSingleQuoted(request.body()))
                            .append(")\"\n");
                }
                script.append("curl --fail --silent --show-error --request ")
                        .append(ExecutionExportShellQuoting.shellSingleQuoted(request.method()))
                        .append(" \"$__step_url\"");
                for (Map.Entry<String, String> header : request.headers().entrySet()) {
                    script.append(" --header \"$(resolve_mcpjvm_template ")
                            .append(ExecutionExportShellQuoting.shellSingleQuoted(
                                    header.getKey() + ": " + header.getValue()))
                            .append(")\"");
                }
                if (request.body() != null) {
                    script.append(" --data-raw \"$__step_body\"");
                }
                script.append("\n")
                        .append("echo ")
                        .append(ExecutionExportShellQuoting.shellSingleQuoted(
                                "[" + request.planName() + ":" + request.stepId() + "] replayed"))
                        .append("\n\n");
            }
        }
    }
}
