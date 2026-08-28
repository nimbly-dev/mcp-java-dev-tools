package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lease;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Filesystem-backed lease shared by MCP server processes using one workspace. */
public final class FileExecutionRunLease implements ExecutionRunLease {

    private final Path workspaceRoot;
    private final Map<String, Path> owned = new ConcurrentHashMap<>();

    /** Creates a lease rooted in the canonical MCP workspace. */
    public FileExecutionRunLease(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /** {@inheritDoc} */
    @Override
    public boolean acquire(String projectName, String suiteRunId) {
        Path lease = leasePath(projectName, suiteRunId);
        try {
            Files.createDirectories(lease.getParent());
            Files.createFile(lease);
            Files.writeString(lease, Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
            owned.put(key(projectName, suiteRunId), lease);
            return true;
        } catch (FileAlreadyExistsException exception) {
            return recoverAbandonedLease(lease) && createRecoveredLease(projectName, suiteRunId, lease);
        } catch (IOException exception) {
            return false;
        }
    }

    private boolean createRecoveredLease(String projectName, String suiteRunId, Path lease) {
        try {
            Files.createFile(lease);
            Files.writeString(lease, Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
            owned.put(key(projectName, suiteRunId), lease);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean recoverAbandonedLease(Path lease) {
        try {
            String owner = Files.readString(lease, StandardCharsets.UTF_8).trim();
            if (ownerAlive(owner)) {
                return false;
            }
            return Files.deleteIfExists(lease);
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean ownerAlive(String owner) {
        try {
            return ProcessHandle.of(Long.parseLong(owner)).map(ProcessHandle::isAlive).orElse(false);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void release(String projectName, String suiteRunId) {
        Path lease = owned.remove(key(projectName, suiteRunId));
        if (lease == null) {
            return;
        }
        try {
            Files.deleteIfExists(lease);
        } catch (IOException exception) {
            owned.put(key(projectName, suiteRunId), lease);
        }
    }

    private Path leasePath(String projectName, String suiteRunId) {
        return workspaceRoot.resolve(".mcpjvm").resolve(segment(projectName)).resolve("suite-runs")
                .resolve(segment(suiteRunId)).resolve("execution.lock");
    }

    private static String key(String projectName, String suiteRunId) {
        return projectName + "\u0000" + suiteRunId;
    }

    private static String segment(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
