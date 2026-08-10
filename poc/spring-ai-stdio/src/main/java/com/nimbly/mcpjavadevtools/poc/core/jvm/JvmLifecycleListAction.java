package com.nimbly.mcpjavadevtools.poc.core.jvm;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class JvmLifecycleListAction {

    private static final int MAX_RESULTS = 128;

    public JvmLifecycleResult execute() {
        try {
            List<JvmDescriptor> jvms = ProcessHandle.allProcesses()
                    .filter(this::isJavaProcess)
                    .filter(process -> process.pid() != ProcessHandle.current().pid())
                    .sorted(Comparator.comparingLong(ProcessHandle::pid))
                    .limit(MAX_RESULTS)
                    .map(this::describe)
                    .flatMap(Optional::stream)
                    .toList();
            return JvmLifecycleResult.ok(jvms);
        } catch (SecurityException exception) {
            return JvmLifecycleResult.blocked("jvm_discovery_failed");
        }
    }

    private boolean isJavaProcess(ProcessHandle process) {
        return process.info().command()
                .map(this::isJavaCommand)
                .orElse(false);
    }

    private boolean isJavaCommand(String command) {
        String executable = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
        return executable.equals("java") || executable.equals("java.exe")
                || executable.equals("javaw") || executable.equals("javaw.exe");
    }

    private Optional<JvmDescriptor> describe(ProcessHandle process) {
        ProcessHandle.Info info = process.info();
        String identityHint = info.command()
                .map(command -> Path.of(command).getFileName().toString())
                .orElse(null);
        Long startTime = info.startInstant().map(instant -> instant.toEpochMilli()).orElse(null);
        return Optional.of(new JvmDescriptor(
                String.valueOf(process.pid()),
                identityHint,
                identityHint == null ? "unavailable" : "sanitized_executable_basename",
                "unknown",
                List.of(),
                startTime));
    }
}
