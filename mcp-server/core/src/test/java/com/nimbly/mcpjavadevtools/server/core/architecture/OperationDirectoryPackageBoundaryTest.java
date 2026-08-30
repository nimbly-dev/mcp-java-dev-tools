package com.nimbly.mcpjavadevtools.server.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Enforces the purpose-owned package disposition for MCPJVM-613. */
class OperationDirectoryPackageBoundaryTest {

    private static final String CORE_PACKAGE_PREFIX =
            "com.nimbly.mcpjavadevtools.server.core.";
    private static final Set<String> ROOT_BOUNDARY = Set.of(
            "CatalogPage.java",
            "CatalogQuery.java",
            "OperationDirectory.java",
            "OperationExecutionResult.java",
            "OperationId.java",
            "OperationInvocation.java",
            "package-info.java");
    private static final Set<String> FORBIDDEN_OPERATION_PACKAGES = Set.of(
            "common", "domain", "helper", "manager", "service", "shared", "util", "utility");
    private static final Map<String, List<String>> REQUIRED_PACKAGE_FILES = Map.ofEntries(
            Map.entry("operation/catalog", List.of(
                    "Operation.java", "OperationCatalog.java", "OperationCatalogEntry.java",
                    "OperationCatalogPageBuilder.java", "OperationCursor.java", "OperationExposure.java")),
            Map.entry("operation/binding", List.of(
                    "OperationRegistration.java", "OperationRegistrationBinding.java",
                    "OperationRegistrationContract.java", "OperationRequestDecoder.java",
                    "OperationExecutor.java", "OperationResultEncoder.java")),
            Map.entry("operation/composition", List.of(
                    "CoreOperationDirectory.java", "CoreOperationDirectoryOwners.java")),
            Map.entry("operation/execution", List.of(
                    "OperationDirectoryException.java", "OperationExecutionException.java",
                    "OperationExecutionStatus.java", "OperationInvocationExecution.java")),
            Map.entry("operation/manifest", List.of(
                    "OperationAlias.java", "OperationArgumentDocumentation.java",
                    "OperationDescriptor.java", "OperationDescriptorMetadata.java",
                    "OperationDocumentation.java", "OperationManifest.java",
                    "OperationManifestAssembler.java", "OperationManifestDocument.java",
                    "OperationManifestLoader.java")),
            Map.entry("operation/manifest/xml", List.of(
                    "OperationManifestXmlReader.java", "OperationManifestXmlStructureValidator.java")),
            Map.entry("operation/schema", List.of(
                    "CanonicalOperationSchema.java", "OperationSchema.java", "OperationSchemaRules.java",
                    "OperationSchemaValidator.java", "CoreOperationResultFields.java",
                    "CoreOperationResultSchemas.java")),
            Map.entry("operation/safety", List.of(
                    "OperationSafetyPolicy.java", "CoreOperationSafetyPolicy.java",
                    "OperationValueRedactor.java")),
            Map.entry("operation/trace", List.of(
                    "OperationLegacyIdentity.java", "OperationTraceEntry.java",
                    "OperationTraceInventory.java", "OperationTraceMetadata.java")),
            Map.entry("feature/artifactmanagement/operation", List.of(
                    "ArtifactOperationRegistrations.java")),
            Map.entry("feature/executionprofileexport/operation", List.of(
                    "ExecutionProfileExportOperationRegistrations.java")),
            Map.entry("feature/executionorchestration/operation", List.of(
                    "ExecutionOrchestrationOperationRegistrations.java")),
            Map.entry("feature/failureanalysis/operation", List.of(
                    "FailureAnalysisOperationRegistrations.java")),
            Map.entry("feature/jvmlifecycle/operation", List.of(
                    "JvmLifecycleOperationRegistrations.java")),
            Map.entry("feature/probe/operation", List.of(
                    "ProbeOperationRegistrations.java", "ProbeOperationSchemas.java")),
            Map.entry("feature/routesynthesis/operation", List.of(
                    "RouteSynthesisOperationRegistrations.java")),
            Map.entry("feature/transportexecution/operation", List.of(
                    "TransportExecutionOperationRegistrations.java")),
            Map.entry("feature/suite/regression/operation", List.of(
                    "RegressionSuiteOperationRegistrations.java")),
            Map.entry("feature/suite/performance/operation", List.of(
                    "PerformanceSuiteOperationRegistrations.java")),
            Map.entry("feature/suite/security/operation", List.of(
                    "SecuritySuiteOperationRegistrations.java")),
            Map.entry("feature/artifactmanagement/model/operation", List.of(
                    "ArtifactOperationArguments.java")),
            Map.entry("feature/executionprofileexport/model/operation", List.of(
                    "ExecutionProfileExportArguments.java")),
            Map.entry("feature/executionorchestration/model/operation", List.of(
                    "ExecutionOrchestrationArguments.java")),
            Map.entry("feature/failureanalysis/model/operation", List.of(
                    "FailureAnalyzeArguments.java", "FailureExpectedFingerprintArguments.java",
                    "FailureInvestigationArguments.java", "FailureLineHitArguments.java",
                    "FailureTerminalArguments.java", "FailureVerifyArguments.java")),
            Map.entry("feature/probe/model/operation", List.of(
                    "ProbeHttpArguments.java", "ProbeOperationArguments.java")),
            Map.entry("feature/transportexecution/model/operation", List.of(
                    "TransportExecuteArguments.java", "TransportExecuteOptionsArguments.java")),
            Map.entry("feature/suite/regression/model/operation", List.of(
                    "RegressionSuiteOperationArguments.java")),
            Map.entry("feature/suite/performance/model/operation", List.of(
                    "PerformanceSuiteOperationArguments.java")),
            Map.entry("feature/suite/security/model/operation", List.of(
                    "SecuritySuiteOperationArguments.java")));

    @Test
    void rootContainsOnlyTheIntentionalDirectoryBoundary() throws IOException {
        Path root = operationRoot();
        try (Stream<Path> paths = Files.list(root)) {
            Set<String> actual = paths
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".java"))
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(actual).containsExactlyInAnyOrderElementsOf(ROOT_BOUNDARY);
        }
    }

    @Test
    void everyDispositionTypeAndPackageDocumentationHasItsPurposeOwnedPath() throws IOException {
        for (Map.Entry<String, List<String>> entry : REQUIRED_PACKAGE_FILES.entrySet()) {
            Path directory = coreSourceRoot().resolve(entry.getKey());
            assertThat(Files.isRegularFile(directory.resolve("package-info.java")))
                    .as("package documentation for %s", entry.getKey())
                    .isTrue();
            for (String fileName : entry.getValue()) {
                assertThat(Files.isRegularFile(directory.resolve(fileName)))
                        .as("%s/%s", entry.getKey(), fileName)
                        .isTrue();
                assertThat(Files.readString(directory.resolve(fileName)))
                        .as("package declaration for %s/%s", entry.getKey(), fileName)
                        .contains("package " + CORE_PACKAGE_PREFIX + entry.getKey().replace('/', '.') + ";");
            }
        }
        assertThat(Files.exists(operationRoot().resolve("SuiteOperationArguments.java")))
                .as("cross-Feature suite argument carrier must be removed")
                .isFalse();
    }

    @Test
    void operationDirectorySubpackagesDoNotUseForbiddenGenericBuckets() throws IOException {
        try (Stream<Path> paths = Files.walk(operationRoot())) {
            Set<String> forbidden = paths
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(FORBIDDEN_OPERATION_PACKAGES::contains)
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(forbidden).isEmpty();
        }
    }

    @Test
    void genericOperationPackagesStayIndependentOfFeatureImplementations() throws IOException {
        Path genericRoot = operationRoot();
        try (Stream<Path> paths = Files.walk(genericRoot)) {
            List<String> violations = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(genericRoot.resolve("composition")))
                    .flatMap(this::featureImports)
                    .toList();
            assertThat(violations).isEmpty();
        }
    }

    @Test
    void capabilityRegistrationFilesUseOnlyTheirOwnFeatureInternals() throws IOException {
        List<String> violations = REQUIRED_PACKAGE_FILES.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("feature/")
                        && entry.getKey().endsWith("/operation"))
                .flatMap(entry -> entry.getValue().stream()
                        .map(fileName -> new RegistrationSource(entry.getKey(), fileName)))
                .flatMap(this::foreignFeatureImports)
                .toList();
        assertThat(violations).isEmpty();
    }

    private Stream<String> featureImports(Path source) {
        try {
            return Files.readAllLines(source).stream()
                    .filter(line -> line.trim().startsWith("import "))
                    .filter(line -> line.contains(CORE_PACKAGE_PREFIX + "feature."))
                    .map(line -> source + ": " + line.trim());
        } catch (IOException exception) {
            throw new IllegalStateException("could not read " + source, exception);
        }
    }

    private Stream<String> foreignFeatureImports(RegistrationSource source) {
        Path path = coreSourceRoot().resolve(source.packagePath()).resolve(source.fileName());
        String ownerPrefix = CORE_PACKAGE_PREFIX + "feature." + source.packagePath()
                .substring("feature/".length(), source.packagePath().indexOf("/operation"))
                .replace('/', '.') + ".";
        try {
            return Files.readAllLines(path).stream()
                    .filter(line -> line.trim().startsWith("import "))
                    .filter(line -> line.contains(CORE_PACKAGE_PREFIX + "feature."))
                    .filter(line -> !line.contains(ownerPrefix))
                    .map(line -> path + ": " + line.trim());
        } catch (IOException exception) {
            throw new IllegalStateException("could not read " + path, exception);
        }
    }

    private Path operationRoot() {
        return coreSourceRoot().resolve("operation");
    }

    private Path coreSourceRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve("mcp-server/core/src/main/java"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("repository root could not be located");
        }
        return current.resolve(
                "mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core");
    }

    private record RegistrationSource(String packagePath, String fileName) {
    }
}
