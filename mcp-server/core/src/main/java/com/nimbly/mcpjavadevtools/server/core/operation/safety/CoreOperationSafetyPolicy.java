package com.nimbly.mcpjavadevtools.server.core.operation.safety;

import java.util.Set;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;

/** Java-owned safety policy for the complete Core operation aggregate. */
public class CoreOperationSafetyPolicy {

    private static final Set<String> CONFIRMATION_REQUIRED = Set.of(
            "artifact_management.probe_config.upsert",
            "artifact_management.probe_config.reload",
            "artifact_management.project_context.upsert",
            "artifact_management.performance_plan.upsert",
            "artifact_management.regression_plan.upsert",
            "artifact_management.security_plan.upsert",
            "artifact_management.run_result.upsert",
            "artifact_management.run_result.rebuild",
            "artifact_management.run_result.backfill",
            "artifact_management.run_result.cutover",
            "artifact_management.run_result.cleanup",
            "artifact_management.execution_export.generate",
            "jvm_lifecycle.attach",
            "jvm_lifecycle.deactivate",
            "probe.actuate",
            "probe.profiler",
            "probe.reset",
            "execution_profile_export.export",
            "transport_execute.execute",
            "execution_orchestration.execute",
            "regression_suite.execute_plan",
            "performance_suite.execute_plan",
            "security_suite.execute_plan");

    private CoreOperationSafetyPolicy() {
    }

    public static OperationSafetyPolicy forDescriptor(OperationDescriptor descriptor) {
        return forOperation(descriptor.operationId().value(), descriptor.trace().sideEffect());
    }

    public static OperationSafetyPolicy forOperation(String operationId) {
        return forOperation(operationId, sideEffect(operationId));
    }

    public static OperationSafetyPolicy forOperation(String operationId, String sideEffect) {
        return new OperationSafetyPolicy(
                sideEffect,
                CONFIRMATION_REQUIRED.contains(operationId),
                "caller_must_not_supply_credentials",
                "redact_sensitive_fields",
                OperationSafetyLimits.DEFAULT_TIMEOUT_MILLIS,
                true,
                OperationSafetyLimits.MAX_INPUT_BYTES,
                OperationSafetyLimits.MAX_OUTPUT_BYTES);
    }

    public static String sideEffect(String operationId) {
        if (operationId.startsWith("artifact_management.")) {
            return artifactSideEffect(operationId);
        }
        if (operationId.startsWith("jvm_lifecycle.")) {
            return jvmSideEffect(operationId);
        }
        if (operationId.startsWith("probe.")) {
            return probeSideEffect(operationId);
        }
        if (operationId.equals("execution_profile_export.export")) {
            return "filesystem_export";
        }
        return otherSideEffect(operationId);
    }

    static String artifactSideEffect(String operationId) {
        if (operationId.endsWith(".read") || operationId.endsWith(".validate")
                || operationId.endsWith(".list")) {
            return "filesystem_read";
        }
        if (operationId.endsWith(".upsert")) {
            return "filesystem_write";
        }
        if (operationId.endsWith(".reload")) {
            return "probe_registry_reload";
        }
        if (operationId.endsWith(".generate")) {
            return "filesystem_export";
        }
        if (operationId.endsWith(".query")) {
            return "sqlite_read";
        }
        if (operationId.contains(".run_result.")
                && (operationId.endsWith(".rebuild") || operationId.endsWith(".backfill")
                        || operationId.endsWith(".cutover") || operationId.endsWith(".cleanup"))) {
            return "sqlite_write";
        }
        throw new IllegalArgumentException("unmapped Core operation safety policy: " + operationId);
    }

    static String jvmSideEffect(String operationId) {
        return switch (operationId) {
            case "jvm_lifecycle.list_jvms" -> "jvm_process_read";
            case "jvm_lifecycle.attach" -> "sidecar_agent_attach";
            case "jvm_lifecycle.deactivate" -> "sidecar_agent_deactivate";
            default -> throw new IllegalArgumentException("unmapped Core operation safety policy: " + operationId);
        };
    }

    static String probeSideEffect(String operationId) {
        if (operationId.endsWith(".check") || operationId.endsWith(".status")) {
            return "probe_endpoint_read";
        }
        if (operationId.endsWith(".reset") || operationId.endsWith(".actuate")) {
            return "probe_endpoint_write";
        }
        if (operationId.endsWith(".wait_for_hit")) {
            return "probe_endpoint_poll";
        }
        if (operationId.endsWith(".capture")) {
            return "probe_capture_read";
        }
        if (operationId.endsWith(".profiler")) {
            return "probe_artifact_write";
        }
        throw new IllegalArgumentException("unmapped Core operation safety policy: " + operationId);
    }

    static String otherSideEffect(String operationId) {
        return switch (operationId) {
            case "route_synthesis.class_methods", "route_synthesis.discover_handlers",
                    "route_synthesis.infer_target" -> "filesystem_read";
            case "route_synthesis.create_recipe" -> "filesystem_write";
            case "failure_analysis.analyze_trace", "failure_analysis.verify_reproduction" -> "evidence_read";
            case "transport_execute.execute" -> "transport_request";
            case "execution_orchestration.execute" -> "filesystem_write";
            case "regression_suite.preflight" -> "filesystem_read";
            case "regression_suite.execute_plan", "performance_suite.execute_plan",
                    "security_suite.execute_plan" -> "filesystem_write";
            default -> throw new IllegalArgumentException("unmapped Core operation safety policy: " + operationId);
        };
    }
}
