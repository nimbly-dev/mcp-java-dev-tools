package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactPathPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/** Resolves explicit or deterministic export identifiers. */
final class ExecutionExportId {

    private static final Pattern EXPORT_ID = Pattern.compile("[A-Za-z0-9._-]+");

    private ExecutionExportId() {
    }

    static String resolve(ArtifactManagementRequest request, String projectName) {
        String requested = request.text("exportId").map(String::trim).orElse("");
        if (!requested.isBlank()) {
            if (!EXPORT_ID.matcher(requested).matches()) {
                throw new ArtifactOperationException("export_id_invalid", "exportId contains unsupported characters");
            }
            ArtifactPathPolicy.validateSegment(requested);
            return requested;
        }
        return "export-" + stable(projectName, request.input());
    }

    private static String stable(String projectName, com.fasterxml.jackson.databind.JsonNode input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((projectName + "\n" + ExecutionExportCanonicalJson.order(input))
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("sha256-");
            for (int index = 0; index < 8; index++) {
                value.append(String.format("%02x", digest[index]));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new ArtifactOperationException(
                    "execution_export_id_failed", "export identifier could not be created");
        }
    }
}
