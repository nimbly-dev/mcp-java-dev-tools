package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.policy.ArtifactRedactionPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded JSON Artifact storage with atomic replacement semantics. */
public final class ArtifactJsonStore {

    private static final long MAX_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 1000;
    private final ObjectMapper mapper;

    /** Creates storage using the Application-provided Jackson mapper. */
    public ArtifactJsonStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Reads and parses one bounded JSON Artifact. */
    public JsonNode read(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                throw new ArtifactOperationException("artifact_missing", "Artifact file does not exist");
            }
            if (Files.size(path) > MAX_BYTES) {
                throw new ArtifactOperationException("artifact_read_limit_exceeded", "Artifact exceeds the read limit");
            }
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            return mapper.readTree(stripBom(raw));
        } catch (ArtifactOperationException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new ArtifactOperationException("artifact_json_invalid", "Artifact JSON is invalid");
        } catch (IOException exception) {
            throw new ArtifactOperationException("artifact_read_failed", "Artifact could not be read");
        }
    }

    /** Writes a JSON Artifact through a same-directory temporary file and move. */
    public void write(Path path, JsonNode value) {
        try {
            Files.createDirectories(path.getParent());
            JsonNode persisted = ArtifactRedactionPolicy.sanitizeJson(value);
            String encoded = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(persisted) + "\n";
            if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                throw new ArtifactOperationException(
                        "artifact_write_limit_exceeded", "Artifact JSON exceeds the write limit");
            }
            Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
            try {
                Files.writeString(temporary, encoded, StandardCharsets.UTF_8);
                moveIntoPlace(temporary, path);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (ArtifactOperationException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new ArtifactOperationException("artifact_json_write_failed", "Artifact JSON could not be encoded");
        } catch (IOException exception) {
            throw new ArtifactOperationException("artifact_write_failed", "Artifact could not be persisted");
        }
    }

    /** Prepares a bounded run file set and restores replaced files if publication fails. */
    public synchronized void writeSet(Map<Path, JsonNode> values) {
        if (values.isEmpty() || values.size() > 4) {
            throw new ArtifactOperationException("artifact_write_failed", "Invalid run Artifact file set");
        }
        Path stage = null;
        Map<Path, Path> backups = new LinkedHashMap<>();
        List<Path> published = new ArrayList<>();
        boolean restored = true;
        try {
            Path parent = values.keySet().iterator().next().getParent();
            Files.createDirectories(parent);
            stage = Files.createTempDirectory(parent, ".artifact-set-");
            Map<Path, Path> prepared = prepareSet(stage, parent, values, backups);
            for (var entry : prepared.entrySet()) {
                moveIntoPlace(entry.getValue(), entry.getKey());
                published.add(entry.getKey());
            }
        } catch (IOException | RuntimeException failure) {
            restored = restoreSet(published, backups, failure);
            if (failure instanceof ArtifactOperationException operationFailure) {
                throw operationFailure;
            }
            throw new ArtifactOperationException("artifact_write_failed", "Run Artifact file set could not be persisted");
        } finally {
            if (stage != null && restored) {
                removeStage(stage);
            }
        }
    }

    private Map<Path, Path> prepareSet(Path stage, Path parent, Map<Path, JsonNode> values,
            Map<Path, Path> backups) throws IOException {
        Map<Path, Path> prepared = new LinkedHashMap<>();
        for (var entry : values.entrySet()) {
            Path target = entry.getKey();
            if (!parent.equals(target.getParent())) {
                throw new ArtifactOperationException("artifact_write_failed", "Run Artifacts must share a directory");
            }
            Path next = stage.resolve("new-" + prepared.size());
            write(next, entry.getValue());
            Path backup = null;
            if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (Files.size(target) > MAX_BYTES) {
                    throw new ArtifactOperationException("artifact_read_limit_exceeded", "Prior run Artifact exceeds the read limit");
                }
                backup = stage.resolve("old-" + prepared.size());
                Files.copy(target, backup, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            }
            backups.put(target, backup);
            prepared.put(target, next);
        }
        return prepared;
    }

    private static boolean restoreSet(List<Path> published, Map<Path, Path> backups, Exception failure) {
        boolean restored = true;
        for (Path target : published.reversed()) {
            try {
                Path backup = backups.get(target);
                if (backup == null) {
                    Files.deleteIfExists(target);
                } else {
                    moveIntoPlace(backup, target);
                }
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                restored = false;
            }
        }
        return restored;
    }

    private static void removeStage(Path stage) {
        try {
            try (var files = Files.list(stage)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
            Files.delete(stage);
        } catch (IOException failure) {
            throw new ArtifactOperationException("artifact_write_failed", "Run Artifact staging files could not be removed");
        }
    }

    /** Writes bounded UTF-8 text through the same atomic replacement path as JSON. */
    public void writeText(Path path, String value) {
        if (value == null) {
            throw new ArtifactOperationException("artifact_text_required", "Artifact text is required");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw new ArtifactOperationException(
                    "artifact_write_limit_exceeded", "Artifact text exceeds the write limit");
        }
        try {
            Files.createDirectories(path.getParent());
            Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
            try {
                Files.write(temporary, bytes);
                moveIntoPlace(temporary, path);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new ArtifactOperationException("artifact_write_failed", "Artifact text could not be persisted");
        }
    }

    /** Reads bounded UTF-8 text, returning an empty value when the optional file is absent. */
    public String readText(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return "";
            }
            if (Files.size(path) > MAX_BYTES) {
                throw new ArtifactOperationException(
                        "artifact_read_limit_exceeded", "Artifact text exceeds the read limit");
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (ArtifactOperationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ArtifactOperationException("artifact_read_failed", "Artifact text could not be read");
        }
    }

    /** Lists directory names deterministically, returning an empty list when absent. */
    public List<String> directories(Path root) {
        return entries(root, true);
    }

    /** Lists file names deterministically, returning an empty list when absent. */
    public List<String> files(Path root) {
        return entries(root, false);
    }

    private static List<String> entries(Path root, boolean directories) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.list(root)) {
            List<String> values = stream.filter(path -> directories ? Files.isDirectory(path) : Files.isRegularFile(path))
                    .map(path -> path.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .limit(MAX_ENTRIES + 1L)
                    .toList();
            if (values.size() > MAX_ENTRIES) {
                throw new ArtifactOperationException(
                        "artifact_listing_limit_exceeded", "Artifact listing exceeds the output limit");
            }
            return values;
        } catch (IOException exception) {
            throw new ArtifactOperationException("artifact_list_failed", "Artifact directory could not be listed");
        }
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
