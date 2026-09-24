package com.nimbly.mcpjavadevtools.server.core.operation.composition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.TrustedSuiteExecution;
import java.util.Objects;

/** Opt-in Core aggregate with workspace-backed direct Suite execution. */
public class TrustedCoreOperationDirectory {
    private TrustedCoreOperationDirectory() {
    }

    public static CoreOperationDirectory create(CoreOperationDirectoryOwners owners,
            ObjectMapper mapper, TrustedSuiteExecution trusted) {
        return new CoreOperationDirectory(owners, mapper,
                Objects.requireNonNull(trusted, "trusted Suite execution must not be null"));
    }
}
