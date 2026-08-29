package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Assembles the released result shape for a persisted execution export. */
final class ExecutionExportResultAssembler {

    private final ArtifactManagementSupport support;

    ExecutionExportResultAssembler(ArtifactManagementSupport support) {
        this.support = support;
    }

    ArtifactManagementResult create(
            ArtifactManagementRequest request, ExecutionExportContext context) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("artifactType", request.artifactType().value());
        details.put("action", request.action().value());
        details.put("projectName", context.projectName());
        details.put("exportId", context.exportId());
        details.put("mode", context.mode());
        details.put("suiteType", context.workload().plans().getFirst().suiteType());
        details.put("executionProfile", context.workload().executionProfile() == null
                ? "ad-hoc" : context.workload().executionProfile());
        details.put("exportDirAbs", context.export().toString());
        details.put("path", context.workspace().paths().relative(context.export()));
        details.put("files", support.jsonStore().files(context.export()));
        details.put("output", output(context.export(), context.mode(),
                context.workload().plans().getFirst().suiteType()));
        return new ArtifactManagementResult(
                "execution_profile_export", "ok", "success", null, null, "", Map.of(), details);
    }

    private Map<String, Object> output(Path export, String mode, String suiteType) {
        String replayName = ExecutionExportMode.replayFileName(mode);
        if ("postman".equals(mode)) {
            return Map.of(
                    "collectionPathAbs", export.resolve(replayName).toString(),
                    "environmentPathAbs", export.resolve("project.env").toString());
        }
        String prefix = "performance".equals(suiteType) ? "performance" : "execution";
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("scriptPathAbs", export.resolve("run-" + prefix + "-profile." + mode).toString());
        String readmeName = "performance".equals(suiteType)
                ? "README.performance." + mode + ".md" : "README." + mode + ".md";
        Path readme = export.resolve(readmeName);
        if (Files.isRegularFile(readme)) {
            output.put("readmePathAbs", readme.toString());
        }
        List<String> jmx = new ExecutionExportJmeterArtifacts().list(export);
        if (!jmx.isEmpty()) {
            output.put("jmeterArtifactPathsAbs", jmx);
        }
        return output;
    }

}
