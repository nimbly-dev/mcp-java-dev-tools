package com.nimbly.mcpjavadevtools.server.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
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
    private static final Set<String> FORBIDDEN_REFERENCE_PREFIXES = Set.of(
            "org.springframework",
            "io.modelcontextprotocol",
            "com.nimbly.mcpjavadevtools.server.application",
            "com.nimbly.mcpjavadevtools.server.configuration",
            "com.nimbly.mcpjavadevtools.server.mcp",
            "com.nimbly.mcpjavadevtools.cli",
            "com.nimbly.mcpjavadevtools.server.cli",
            "com.nimbly.mcpjavadevtools.sidecar",
            "com.nimbly.mcpjavadevtools.server.sidecar",
            "com.nimbly.mcpjavadevtools.agent");
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
            Map.entry("feature/suite/regression/model/operation", List.of(
                    "RegressionSuiteOperationArguments.java")),
            Map.entry("feature/suite/regression/operation", List.of(
                    "RegressionSuiteOperationRegistrations.java")),
            Map.entry("feature/suite/performance/model/operation", List.of(
                    "PerformanceSuiteOperationArguments.java")),
            Map.entry("feature/suite/performance/operation", List.of(
                    "PerformanceSuiteOperationRegistrations.java")),
            Map.entry("feature/suite/security/model/operation", List.of(
                    "SecuritySuiteOperationArguments.java")),
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
                    "TransportExecuteArguments.java", "TransportExecuteOptionsArguments.java")));

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
    void genericOperationPackagesContainOnlyTheDeclaredPurposeOwnedFiles() throws IOException {
        Path root = operationRoot();
        try (Stream<Path> paths = Files.walk(root)) {
            Set<String> actual = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(root::relativize)
                    .map(Path::toString)
                    .map(path -> path.replace('\\', '/'))
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(actual).containsExactlyInAnyOrderElementsOf(expectedGenericFiles());
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
        Path sharedSuiteModel = coreSourceRoot().resolve("feature/suite/model");
        if (Files.exists(sharedSuiteModel)) {
            try (Stream<Path> paths = Files.walk(sharedSuiteModel)) {
                assertThat(paths.anyMatch(path -> Files.isRegularFile(path)
                        && path.getFileName().toString().endsWith(".java")))
                        .as("cross-Feature suite model package must contain no Java sources")
                        .isFalse();
            }
        }
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
                    .flatMap(this::featureReferences)
                    .toList();
            assertThat(violations).isEmpty();
        }
    }

    @Test
    void genericOperationPackagesStayIndependentOfForbiddenExternalBoundaries() throws IOException {
        Path genericRoot = operationRoot();
        try (Stream<Path> paths = Files.walk(genericRoot)) {
            List<String> violations = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(this::forbiddenReferences)
                    .toList();
            assertThat(violations).isEmpty();
        }
    }

    @Test
    void capabilityRegistrationFilesUseOnlyTheirOwnFeatureInternals() throws IOException {
        List<String> violations = registrationSources()
                .flatMap(this::foreignFeatureImports)
                .toList();
        assertThat(violations).isEmpty();
    }

    private Stream<RegistrationSource> registrationSources() throws IOException {
        Path featureRoot = coreSourceRoot().resolve("feature");
        try (Stream<Path> paths = Files.walk(featureRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("package-info.java"))
                    .filter(path -> path.getParent().getFileName().toString().equals("operation"))
                    .filter(path -> isCapabilityRegistrationFile(path.getFileName().toString()))
                    .map(path -> new RegistrationSource(
                            coreSourceRoot().relativize(path.getParent()).toString().replace('\\', '/'),
                            path.getFileName().toString()))
                    .toList()
                    .stream();
        }
    }

    private boolean isCapabilityRegistrationFile(String fileName) {
        return fileName.endsWith("OperationRegistrations.java")
                || fileName.endsWith("OperationSchemas.java")
                || fileName.endsWith("OperationBindings.java");
    }

    @Test
    void astReferenceScanSeesQualifiedCrossFeatureAndForbiddenReferences() {
        Set<String> references = parseReferences(
                "fixtures/QualifiedOperationReferences.java",
                "package fixtures;\n"
                        + "class QualifiedOperationReferences {\n"
                        + "    void execute() {\n"
                        + "        com.nimbly.mcpjavadevtools.server.core.feature.probe.operation."
                        + "ProbeOperationSchemas schemas = null;\n"
                        + "        org.springframework.ai.mcp.annotation.McpTool tool = null;\n"
                        + "    }\n"
                        + "}\n");

        assertThat(references)
                .contains("com.nimbly.mcpjavadevtools.server.core.feature.probe.operation.ProbeOperationSchemas")
                .contains("org.springframework.ai.mcp.annotation.McpTool");
        assertThat(references).anyMatch(this::isForbiddenReference);
    }

    @Test
    void forbiddenReferencePolicyAlsoCoversOperationComposition() {
        Set<String> references = parseReferences(
                "operation/composition/QualifiedCompositionReference.java",
                "package operation.composition;\n"
                        + "class QualifiedCompositionReference {\n"
                        + "    void compose() {\n"
                        + "        org.springframework.ai.mcp.annotation.McpTool tool = null;\n"
                        + "    }\n"
                        + "}\n");

        assertThat(references).anyMatch(this::isForbiddenReference);
    }

    private Set<String> expectedGenericFiles() {
        Set<String> expected = new HashSet<>(ROOT_BOUNDARY);
        REQUIRED_PACKAGE_FILES.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("operation/"))
                .forEach(entry -> {
                    String directory = entry.getKey().substring("operation/".length());
                    expected.add(directory + "/package-info.java");
                    entry.getValue().forEach(fileName -> expected.add(directory + "/" + fileName));
                });
        return Set.copyOf(expected);
    }

    private Stream<String> featureReferences(Path source) {
        try {
            return parseReferences(source.toString(), Files.readString(source)).stream()
                    .filter(reference -> reference.startsWith(CORE_PACKAGE_PREFIX + "feature."))
                    .map(reference -> source + ": " + reference);
        } catch (IOException exception) {
            throw new IllegalStateException("could not read " + source, exception);
        }
    }

    private Stream<String> forbiddenReferences(Path source) {
        try {
            return parseReferences(source.toString(), Files.readString(source)).stream()
                    .filter(this::isForbiddenReference)
                    .map(reference -> source + ": " + reference);
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
            return parseReferences(path.toString(), Files.readString(path)).stream()
                    .filter(reference -> reference.startsWith(CORE_PACKAGE_PREFIX + "feature."))
                    .filter(reference -> !reference.startsWith(ownerPrefix))
                    .map(reference -> path + ": " + reference);
        } catch (IOException exception) {
            throw new IllegalStateException("could not read " + path, exception);
        }
    }

    private boolean isForbiddenReference(String reference) {
        return FORBIDDEN_REFERENCE_PREFIXES.stream()
                .anyMatch(prefix -> reference.equals(prefix) || reference.startsWith(prefix + "."));
    }

    private static Set<String> parseReferences(String sourceName, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is required for package boundary enforcement");
        }
        String normalizedName = sourceName.replace('\\', '/');
        String fileName = normalizedName.substring(normalizedName.lastIndexOf('/') + 1);
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                null, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavaFileObject sourceFile = new SourceFileObject(fileName, source);
            JavacTask task = (JavacTask) compiler.getTask(
                    null,
                    fileManager,
                    OperationDirectoryPackageBoundaryTest::ignoreDiagnostic,
                    List.of("-proc:none", "--source", "21"),
                    null,
                    List.of(sourceFile));
            CompilationUnitTree unit = task.parse().iterator().next();
            Set<String> references = new HashSet<>();
            unit.getImports().forEach(importTree ->
                    references.add(importTree.getQualifiedIdentifier().toString()));
            new TreePathScanner<Void, Set<String>>() {
                @Override
                public Void visitMemberSelect(MemberSelectTree node, Set<String> names) {
                    TreePath parent = getCurrentPath().getParentPath();
                    if (parent == null || !(parent.getLeaf() instanceof MemberSelectTree)) {
                        names.add(node.toString());
                    }
                    return super.visitMemberSelect(node, names);
                }
            }.scan(unit, references);
            return Set.copyOf(references);
        } catch (IOException exception) {
            throw new IllegalStateException("could not parse " + sourceName, exception);
        }
    }

    private static void ignoreDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
        // Unresolved symbols do not affect syntax-tree reference collection.
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

    private static final class SourceFileObject extends SimpleJavaFileObject {

        private final String source;

        private SourceFileObject(String fileName, String source) {
            super(URI.create("string:///" + fileName), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
