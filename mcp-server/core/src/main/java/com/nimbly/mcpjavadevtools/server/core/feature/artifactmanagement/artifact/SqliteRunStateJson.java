package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.policy.ArtifactRedactionPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

/** Applies the bounded read and redacted persistence rules for run-state JSON. */
final class SqliteRunStateJson {

    private final ObjectMapper mapper;

    SqliteRunStateJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    JsonNode readBoundedJson(Path path) throws IOException {
        if (Files.size(path) > 4L * 1024L * 1024L) {
            throw new IOException("run Artifact exceeds the read limit");
        }
        return mapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
    }

    String boundedJson(JsonNode value) throws SQLException {
        try {
            String encoded = mapper.writeValueAsString(ArtifactRedactionPolicy.sanitizeJson(value));
            if (encoded.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
                throw new SQLException("state surface exceeds the persistence limit");
            }
            return encoded;
        } catch (IOException exception) {
            throw new SQLException("state surface could not be encoded", exception);
        }
    }
}
