package com.nimbly.mcpjavadevtools.server.core.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.FailureAnalysisAction;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.TreeSet;
import com.nimbly.mcpjavadevtools.server.core.operation.ArtifactOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.operation.ExecutionOrchestrationOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.operation.ExecutionProfileExportOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.operation.FailureAnalysisOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.operation.JvmLifecycleOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.operation.ProbeOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.operation.RouteSynthesisOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.operation.PerformanceSuiteOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.operation.RegressionSuiteOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.operation.SecuritySuiteOperationRegistrations;
import com.nimbly.mcpjavadevtools.server.core.operation.TransportExecutionOperationRegistrations;

/** Production Core composition of every operation documented by the 610 manifest. */
public final class CoreOperationDirectory {

    /** The approved 610 aggregate is intentionally closed at fifty-four operations. */
    public static final int EXPECTED_OPERATION_COUNT = 54;

    private final OperationDirectory directory;

    /** Creates the complete aggregate with the supplied Core-owned JSON mapper. */
    public CoreOperationDirectory(
            CoreOperationDirectoryOwners owners,
            ObjectMapper mapper) {
        ObjectMapper aggregateMapper = Objects.requireNonNull(mapper, "mapper must not be null")
                .copy().registerModule(new Jdk8Module());
        directory = new OperationDirectory(
                registrations(Objects.requireNonNull(owners, "owners must not be null"),
                        aggregateMapper),
                OperationManifestLoader.loadBuiltIn(),
                aggregateMapper);
    }

    /** Creates the complete aggregate with the default transport-neutral mapper. */
    public CoreOperationDirectory(CoreOperationDirectoryOwners owners) {
        this(owners, new ObjectMapper());
    }

    /** @return the assembled immutable Core operation directory */
    public OperationDirectory directory() {
        return directory;
    }

    /** @return the generated immutable manifest for every approved operation */
    public OperationManifest manifest() {
        return directory.manifest();
    }

    /** @return the generated legacy-to-CDE trace inventory */
    public List<OperationTraceEntry> traceInventory() {
        return directory.traceInventory();
    }

    static List<OperationRegistration<?, ?>> registrations(
            CoreOperationDirectoryOwners owners,
            ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        List<OperationRegistration<?, ?>> aggregate = Stream.of(
                ArtifactOperationRegistrations.create(owners.artifact(), mapper),
                JvmLifecycleOperationRegistrations.create(owners.jvmLifecycle(), mapper),
                ProbeOperationRegistrations.create(owners.probe(), mapper),
                ExecutionProfileExportOperationRegistrations.create(owners.export(), mapper),
                RouteSynthesisOperationRegistrations.create(owners.routes(), mapper),
                FailureAnalysisOperationRegistrations.create(owners.failures(), mapper),
                TransportExecutionOperationRegistrations.create(owners.transport(), mapper),
                ExecutionOrchestrationOperationRegistrations.create(owners.orchestration(), mapper),
                RegressionSuiteOperationRegistrations.create(owners.regression(), mapper),
                PerformanceSuiteOperationRegistrations.create(owners.performance(), mapper),
                SecuritySuiteOperationRegistrations.create(owners.security(), mapper))
                .flatMap(List::stream)
                .toList();
        if (aggregate.size() != EXPECTED_OPERATION_COUNT) {
            throw new IllegalArgumentException(
                    "Core operation aggregate must contain " + EXPECTED_OPERATION_COUNT
                            + " registrations but contains " + aggregate.size());
        }
        Set<OperationId> actual = aggregate.stream()
                .map(registration -> registration.descriptor().operationId())
                .collect(java.util.stream.Collectors.toSet());
        Set<OperationId> expected = expectedOperationIds();
        if (!actual.equals(expected)) {
            Set<OperationId> missing = new TreeSet<>(expected);
            missing.removeAll(actual);
            Set<OperationId> unexpected = new TreeSet<>(actual);
            unexpected.removeAll(expected);
            throw new IllegalArgumentException(
                    "Core operation aggregate IDs do not match the approved set; missing="
                            + missing + ", unexpected=" + unexpected);
        }
        return aggregate;
    }

    static Set<OperationId> expectedOperationIds() {
        Set<OperationId> expected = new TreeSet<>();
        for (ArtifactManagementAction action : ArtifactManagementAction.values()) {
            expected.add(OperationId.fromLegacy("artifact_management", action.routeId()));
        }
        for (JvmLifecycleAction action : JvmLifecycleAction.values()) {
            expected.add(OperationId.fromLegacy("jvm_lifecycle", action.value()));
        }
        for (ProbeAction action : ProbeAction.values()) {
            expected.add(OperationId.fromLegacy("probe", action.value()));
        }
        for (RouteSynthesisAction action : RouteSynthesisAction.values()) {
            expected.add(OperationId.fromLegacy("route_synthesis", action.value()));
        }
        for (FailureAnalysisAction action : FailureAnalysisAction.values()) {
            expected.add(OperationId.fromLegacy("failure_analysis", action.value()));
        }
        expected.addAll(Set.of(
                OperationId.of("execution_profile_export.export"),
                OperationId.of("transport_execute.execute"),
                OperationId.of("execution_orchestration.execute"),
                OperationId.of("regression_suite.execute_plan"),
                OperationId.of("regression_suite.preflight"),
                OperationId.of("performance_suite.execute_plan"),
                OperationId.of("security_suite.execute_plan")));
        return Set.copyOf(expected);
    }
}
