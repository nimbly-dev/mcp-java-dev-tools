package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import java.util.Map;

/** Appends HTTP workload requests to an exported PowerShell replay. */
final class ExecutionExportPowerShellWorkload {

    void append(StringBuilder script, ExecutionExportWorkload.Workload workload) {
        for (ExecutionExportWorkload.PlanWorkload plan : workload.plans()) {
            script.append("Write-Host ").append(ExecutionExportShellQuoting.powerShellSingleQuoted(
                    "[" + plan.suiteType() + ":" + plan.planName() + "] replay workload")).append("\n");
            for (ExecutionExportWorkload.WorkloadRequest request : plan.requests()) {
                script.append("$__step_url = Resolve-McpJvmTemplate ")
                        .append(ExecutionExportShellQuoting.powerShellSingleQuoted(request.url())).append("\n")
                        .append("$__step_headers = @{}\n");
                for (Map.Entry<String, String> header : request.headers().entrySet()) {
                    script.append("$__step_headers[")
                            .append(ExecutionExportShellQuoting.powerShellSingleQuoted(header.getKey()))
                            .append("] = Resolve-McpJvmTemplate ")
                            .append(ExecutionExportShellQuoting.powerShellSingleQuoted(header.getValue()))
                            .append("\n");
                }
                script.append("$__step_request = @{ Method = ")
                        .append(ExecutionExportShellQuoting.powerShellSingleQuoted(request.method()))
                        .append("; Uri = $__step_url; Headers = $__step_headers; UseBasicParsing = $true }\n");
                if (request.body() != null) {
                    script.append("$__step_raw_body = ")
                            .append(ExecutionExportShellQuoting.powerShellSingleQuoted(request.body())).append("\n")
                            .append("$__step_request.Body = Resolve-McpJvmTemplate $__step_raw_body\n");
                }
                script.append("$__step_response = Invoke-WebRequest @__step_request\n")
                        .append("if ($__step_response.StatusCode -lt 200 -or $__step_response.StatusCode -ge 300) { throw ")
                        .append(ExecutionExportShellQuoting.powerShellSingleQuoted(
                                "workload_step_failed:" + request.stepId())).append(" }\n")
                        .append("Write-Host ")
                        .append(ExecutionExportShellQuoting.powerShellSingleQuoted(
                                "[" + request.planName() + ":" + request.stepId() + "] status="))
                        .append("$__step_response.StatusCode\n\n");
            }
        }
    }
}
