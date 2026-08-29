package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Owns the filesystem lock used to serialize project-owned SQLite mutations. */
final class SqliteRunStateLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private SqliteRunStateLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static SqliteRunStateLock acquire(Path databasePath) {
        Path lockPath = databasePath.resolveSibling(databasePath.getFileName() + ".lock");
        try {
            Files.createDirectories(lockPath.getParent());
            FileChannel channel = FileChannel.open(
                    lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    throw new ArtifactOperationException(
                            "state_store_busy", "SQLite state store is locked by another operation");
                }
                return new SqliteRunStateLock(channel, lock);
            } catch (OverlappingFileLockException exception) {
                channel.close();
                throw new ArtifactOperationException(
                        "state_store_busy", "SQLite state store is locked by another operation");
            }
        } catch (IOException exception) {
            throw new ArtifactOperationException(
                    "state_store_lock_failed", "SQLite state-store lock could not be acquired");
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
            channel.close();
        } catch (IOException exception) {
            throw new ArtifactOperationException(
                    "state_store_lock_release_failed", "SQLite state-store lock could not be released");
        }
    }
}
