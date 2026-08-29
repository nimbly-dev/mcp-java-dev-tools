package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Copies bounded file-backed script arguments into the export package. */
final class ExecutionExportScriptFileCopier {

    private final ArtifactManagementSupport support;

    ExecutionExportScriptFileCopier(ArtifactManagementSupport support) {
        this.support = support;
    }

    String copyIfFile(ExecutionExportContext context, String scriptRoot, String value) {
        String extension = value.toLowerCase(Locale.ROOT);
        if (!(extension.endsWith(".ps1") || extension.endsWith(".sh") || extension.endsWith(".bash")
                || extension.endsWith(".js") || extension.endsWith(".mjs") || extension.endsWith(".py"))) {
            return value;
        }
        return copy(context, scriptRoot, value);
    }

    String copy(ExecutionExportContext context, String scriptRoot, String value) {
        Path source = context.workspace().paths().check(context.workspace().root().resolve(value));
        if (!Files.isRegularFile(source)) {
            throw new ArtifactOperationException(
                    "execution_export_script_missing", "Referenced execution-profile script file is unavailable");
        }
        try {
            if (Files.size(source) > 4L * 1024L * 1024L) {
                throw new ArtifactOperationException(
                        "execution_export_script_too_large",
                        "Referenced execution-profile script exceeds the read limit");
            }
        } catch (IOException exception) {
            throw new ArtifactOperationException(
                    "execution_export_script_read_failed", "Referenced execution-profile script could not be read");
        }
        String fileName = ExecutionExportScriptFileName.safe(source.getFileName().toString());
        Path target = context.workspace().paths().resolve(
                ".mcpjvm", context.projectName(), "exports", context.exportId(), "scripts",
                scriptRoot.substring(scriptRoot.indexOf('/') + 1), fileName);
        support.jsonStore().writeText(target, support.jsonStore().readText(source));
        return "__MCPJVM_SCRIPT__" + scriptRoot + "/" + fileName;
    }
}
