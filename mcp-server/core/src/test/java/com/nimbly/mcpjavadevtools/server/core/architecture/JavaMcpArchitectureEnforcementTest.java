package com.nimbly.mcpjavadevtools.server.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** Enforces bounded operation ownership on the migrated reference graph. */
class JavaMcpArchitectureEnforcementTest {

    private static final int MAX_CLASS_LINES = 250;
    private static final int MAX_METHODS = 12;
    private static final Set<String> GENERIC_OWNER_NAMES = Set.of(
            "service", "manager", "helper", "util", "utility", "common", "shared");
    private static final Set<String> VISIBILITY_LAUNDERING_NAMES = Set.of(
            "helper", "util", "internal", "delegate", "passthrough");

    @Test
    void migratedReferenceProductionGraphSatisfiesBoundedOwnershipRules() throws IOException {
        for (Path sourceFile : enforcedProductionFiles()) {
            for (SourceShape shape : SourceShape.from(sourceFile)) {
                assertThat(shape.violations())
                        .as("architecture violations in %s", sourceFile)
                        .isEmpty();
            }
        }
    }

    @Test
    void everyRelocatedOperationSourceIsCoveredByAstEnforcement() throws IOException {
        Set<Path> enforced = Set.copyOf(enforcedProductionFiles());
        assertThat(enforced)
                .as("all MCPJVM-613 relocated operation sources must be AST-enforced")
                .containsAll(relocatedOperationFiles(repositoryRoot()));
    }

    @Test
    void requiresTheTrackedJavaMcpContractAmendment() throws IOException {
        Path contract = repositoryRoot().resolve(
                "docs/architecture/java-mcp-catalog-describe-execute-contract.md");

        assertThat(Files.isRegularFile(contract))
                .as("tracked Java MCP contract must exist at %s", contract)
                .isTrue();
        assertThat(Files.readString(contract))
                .contains(
                        "Status: normative for existing capability-owned Java MCP catalogs;",
                        "## #610/#611 CDE target and transition",
                        "## Catalog-Describe-Execute",
                        "OperationExposure",
                        "maximum 250 source lines per class",
                        "Java compiler AST");
    }

    @Test
    void acceptsACompliantBoundedOperationFixture() {
        SourceShape shape = SourceShape.from(
                "fixtures/BoundedOperation.java",
                "package fixtures;\n"
                        + "public class BoundedOperation {\n"
                        + "    public String execute(String input) { return input; }\n"
                        + "}\n");

        assertThat(shape.violations()).isEmpty();
    }

    @Test
    void acceptsCohesivePrivateMethodCalls() {
        SourceShape shape = SourceShape.from(
                "fixtures/PrivateMethods.java",
                "package fixtures;\n"
                        + "public class PrivateMethods {\n"
                        + "    public String execute() { return first(); }\n"
                        + "    private String first() { return second(); }\n"
                        + "    private String second() { return \"done\"; }\n"
                        + "}\n");

        assertThat(shape.violations()).isEmpty();
    }

    @Test
    void rejectsClassSprawlAndNestedWorkflowTypesInFixtures() {
        StringBuilder manyMethods = new StringBuilder("package fixtures;\npublic class ManyMethods {\n");
        for (int index = 0; index < 13; index++) {
            manyMethods.append("    public String method").append(index).append("() { return \"x\"; }\n");
        }
        manyMethods.append("}\n");

        SourceShape methodShape = SourceShape.from("fixtures/ManyMethods.java", manyMethods.toString());
        StringBuilder longClass = new StringBuilder("package fixtures;\npublic class LongClass {\n");
        for (int index = 0; index < 251; index++) {
            longClass.append("    // bounded class-length fixture line\n");
        }
        longClass.append("}\n");
        SourceShape longShape = SourceShape.from("fixtures/LongClass.java", longClass.toString());
        SourceShape nestedShape = SourceShape.from(
                "fixtures/NestedWorkflow.java",
                "package fixtures;\n"
                        + "public class NestedWorkflow {\n"
                        + "    static class InnerWorkflow {}\n"
                        + "}\n");

        assertThat(methodShape.violations()).contains("method count: 13");
        assertThat(longShape.violations()).anyMatch(violation -> violation.startsWith("class length:"));
        assertThat(nestedShape.violations()).contains("nested workflow types: 1");
    }

    @Test
    void rejectsGenericOwnersAndVisibilityLaunderingInFixtures() {
        SourceShape genericOwner = SourceShape.from(
                "fixtures/ArtifactService.java",
                "package fixtures;\npublic class ArtifactService {}\n");
        SourceShape sharedPrimitive = SourceShape.from(
                "fixtures/shared/Primitive.java",
                "package fixtures.shared;\npublic class Primitive {}\n");
        SourceShape laundering = SourceShape.from(
                "fixtures/VisibilityLaundering.java",
                "package fixtures;\n"
                        + "public class VisibilityLaundering {\n"
                        + "    public String delegate() { return \"x\"; }\n"
                        + "}\n");

        assertThat(genericOwner.violations()).contains("generic owner name: ArtifactService");
        assertThat(sharedPrimitive.violations()).contains("generic owner name: Primitive");
        assertThat(laundering.violations()).contains("visibility laundering: delegate");
    }

    private List<Path> enforcedProductionFiles() throws IOException {
        Path root = repositoryRoot();
        List<Path> files = new ArrayList<>();
        addJavaFiles(files, root.resolve(
                "mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/operation"));
        files.addAll(relocatedOperationFiles(root));
        addJavaFiles(files, root.resolve(
                "mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/feature/"
                        + "artifactmanagement/operation"));
        addArtifactExportMigrationFiles(files, root);
        addArtifactRunStateMigrationFiles(files, root);
        addJavaFiles(files, root.resolve(
                "mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/feature/executionprofileexport"));
        addJavaFiles(files, root.resolve(
                "mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/feature/probe/operation"));
        addJavaFiles(files, root.resolve(
                "mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/feature/jvmlifecycle/operation"));
        addJavaFiles(files, root.resolve(
                "mcp-server/application/src/main/java/com/nimbly/mcpjavadevtools/server/configuration/"
                        + "ExecutionProfileExportConfiguration.java"));
        addJavaFiles(files, root.resolve(
                "mcp-server/application/src/main/java/com/nimbly/mcpjavadevtools/server/mcp/tools/"
                        + "executionprofileexport/ExecutionProfileExportMcpRequest.java"));
        addJavaFiles(files, root.resolve(
                "mcp-server/application/src/main/java/com/nimbly/mcpjavadevtools/server/mcp/tools/"
                        + "executionprofileexport/ExecutionProfileExportMcpRequestMapper.java"));
        addJavaFiles(files, root.resolve(
                "mcp-server/application/src/main/java/com/nimbly/mcpjavadevtools/server/mcp/tools/"
                        + "executionprofileexport/ExecutionProfileExportMcpResponseMapper.java"));
        addJavaFiles(files, root.resolve(
                "mcp-server/application/src/main/java/com/nimbly/mcpjavadevtools/server/mcp/tools/"
                        + "executionprofileexport/ExecutionProfileExportMcpTool.java"));
        return files.stream().distinct().sorted().toList();
    }

    private List<Path> relocatedOperationFiles(Path root) throws IOException {
        String[] packagePaths = {
            "feature/artifactmanagement/model/operation",
            "feature/artifactmanagement/operation",
            "feature/executionorchestration/model/operation",
            "feature/executionorchestration/operation",
            "feature/executionprofileexport/model/operation",
            "feature/executionprofileexport/operation",
            "feature/failureanalysis/model/operation",
            "feature/failureanalysis/operation",
            "feature/jvmlifecycle/operation",
            "feature/probe/model/operation",
            "feature/probe/operation",
            "feature/routesynthesis/operation",
            "feature/suite/regression/model/operation",
            "feature/suite/regression/operation",
            "feature/suite/performance/model/operation",
            "feature/suite/performance/operation",
            "feature/suite/security/model/operation",
            "feature/suite/security/operation",
            "feature/transportexecution/model/operation",
            "feature/transportexecution/operation"
        };
        List<Path> files = new ArrayList<>();
        Path sourceRoot = root.resolve(
                "mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core");
        for (String packagePath : packagePaths) {
            addJavaFiles(files, sourceRoot.resolve(packagePath));
        }
        return files;
    }

    private static void addArtifactExportMigrationFiles(List<Path> files, Path root) throws IOException {
        String[] names = {
            "ExecutionExportCanonicalJson.java",
            "ExecutionExportContext.java",
            "ExecutionExportEnvironmentFormat.java",
            "ExecutionExportEnvironmentWriter.java",
            "ExecutionExportGenerator.java",
            "ExecutionExportId.java",
            "ExecutionExportJmeterArtifacts.java",
            "ExecutionExportManifestWriter.java",
            "ExecutionExportMode.java",
            "ExecutionExportOperations.java",
            "ExecutionExportPackageWriter.java",
            "ExecutionExportPostmanRenderer.java",
            "ExecutionExportPowerShellHeader.java",
            "ExecutionExportPowerShellRenderer.java",
            "ExecutionExportPowerShellScripts.java",
            "ExecutionExportPowerShellWorkload.java",
            "ExecutionExportProfileSelector.java",
            "ExecutionExportReader.java",
            "ExecutionExportReplayFiles.java",
            "ExecutionExportReplayFileWriter.java",
            "ExecutionExportReplayReadme.java",
            "ExecutionExportReplayRenderer.java",
            "ExecutionExportReplayWriter.java",
            "ExecutionExportResultAssembler.java",
            "ExecutionExportRuntimeRenderer.java",
            "ExecutionExportScalarValues.java",
            "ExecutionExportScriptArguments.java",
            "ExecutionExportScriptFileCopier.java",
            "ExecutionExportScriptFileName.java",
            "ExecutionExportScriptInvocation.java",
            "ExecutionExportScriptPhase.java",
            "ExecutionExportScriptPreparation.java",
            "ExecutionExportScriptReference.java",
            "ExecutionExportShellQuoting.java",
            "ExecutionExportShellRenderer.java",
            "ExecutionExportShellScripts.java",
            "ExecutionExportShellWorkload.java",
            "ExecutionExportWorkloadKind.java",
            "ExecutionExportWorkloadResolver.java"
        };
        Path exportRoot = root.resolve(
                "mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/feature/"
                        + "artifactmanagement/artifact/export");
        for (String name : names) {
            addJavaFiles(files, exportRoot.resolve(name));
        }
    }

    private static void addArtifactRunStateMigrationFiles(List<Path> files, Path root) throws IOException {
        String[] names = {
            "SqliteLegacyCorrelation.java",
            "SqliteRebuildCounts.java",
            "SqliteRunProjection.java",
            "SqliteRunStateArtifactReader.java",
            "SqliteRunStateCleanup.java",
            "SqliteRunStateCutover.java",
            "SqliteRunStateDatabase.java",
            "SqliteRunStateJson.java",
            "SqliteRunStateLegacyBackfill.java",
            "SqliteRunStateLock.java",
            "SqliteRunStateProjectionRebuilder.java",
            "SqliteRunStateProjectionWriter.java",
            "SqliteRunStateQuery.java",
            "SqliteRunStateQueryContext.java",
            "SqliteRunStateQueryContract.java",
            "SqliteRunStateQueryPlan.java",
            "SqliteRunStateQueryPlanBuilder.java",
            "SqliteRunStateQueryRows.java",
            "SqliteRunStateRowMapper.java",
            "SqliteRunStateStore.java"
        };
        Path artifactRoot = root.resolve(
                "mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/feature/"
                        + "artifactmanagement/artifact");
        for (String name : names) {
            addJavaFiles(files, artifactRoot.resolve(name));
        }
    }

    private static void addJavaFiles(List<Path> files, Path root) throws IOException {
        if (Files.isDirectory(root)) {
            try (var paths = Files.walk(root)) {
                paths.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !path.getFileName().toString().equals("package-info.java"))
                        .forEach(files::add);
            }
        } else if (Files.isRegularFile(root) && root.toString().endsWith(".java")) {
            files.add(root);
        } else {
            throw new IllegalStateException("missing enforcement root: " + root);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve("mcp-server/core/src/main/java"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("repository root could not be located");
        }
        return current;
    }

    private static List<SourceShape> parseSource(String sourceName, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is required for AST architecture enforcement");
        }
        String fileName = sourceName.substring(sourceName.lastIndexOf('/') + 1)
                .replace('\\', '/');
        if (!fileName.endsWith(".java")) {
            fileName = "Source.java";
        }
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                null, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavaFileObject sourceFile = new SourceFileObject(fileName, source);
            JavacTask task = (JavacTask) compiler.getTask(
                    null,
                    fileManager,
                    JavaMcpArchitectureEnforcementTest::ignoreDiagnostic,
                    List.of("-proc:none", "--source", "21"),
                    null,
                    List.of(sourceFile));
            CompilationUnitTree unit = task.parse().iterator().next();
            SourcePositions positions = Trees.instance(task).getSourcePositions();
            AstCollector collector = new AstCollector(sourceName, source, unit, positions);
            collector.scan(unit, null);
            return collector.shapes();
        } catch (IOException exception) {
            throw new IllegalStateException("could not parse source: " + sourceName, exception);
        }
    }

    private static void ignoreDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
        // Parsing is the enforcement input; unresolved symbols are irrelevant here.
    }

    private static class SourceFileObject extends SimpleJavaFileObject {

        private final String source;

        SourceFileObject(String fileName, String source) {
            super(URI.create("string:///" + fileName), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static class AstCollector extends TreePathScanner<Void, Void> {

        private final String sourceName;
        private final String source;
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final Deque<TypeBuilder> types = new ArrayDeque<>();
        private final List<TypeBuilder> collected = new ArrayList<>();

        AstCollector(String sourceName, String source, CompilationUnitTree unit, SourcePositions positions) {
            this.sourceName = sourceName;
            this.source = source;
            this.unit = unit;
            this.positions = positions;
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            TypeBuilder parent = types.peek();
            TypeBuilder builder = new TypeBuilder(node, parent == null ? null : parent.root());
            if (parent == null) {
                builder.setRoot(builder);
            } else {
                parent.root().incrementNestedTypeCount();
            }
            collected.add(builder);
            types.push(builder);
            super.visitClass(node, unused);
            types.pop();
            return null;
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            TypeBuilder owner = types.peek();
            if (owner != null && node.getReturnType() != null) {
                owner.addMethod(MethodShape.from(node));
            }
            return super.visitMethod(node, unused);
        }

        List<SourceShape> shapes() {
            return collected.stream()
                    .filter(builder -> builder.root() == builder)
                    .map(builder -> builder.toShape(sourceName, source, unit, positions))
                    .toList();
        }
    }

    private static class TypeBuilder {

        private final ClassTree type;
        private TypeBuilder root;
        private final List<MethodShape> methods = new ArrayList<>();
        private int nestedTypeCount;

        TypeBuilder(ClassTree type, TypeBuilder root) {
            this.type = type;
            this.root = root;
        }

        TypeBuilder root() {
            return root == null ? this : root;
        }

        void setRoot(TypeBuilder root) {
            if (this.root != null) {
                throw new IllegalStateException("type root already assigned");
            }
            this.root = root;
        }

        void incrementNestedTypeCount() {
            nestedTypeCount++;
        }

        void addMethod(MethodShape method) {
            methods.add(method);
        }

        SourceShape toShape(
                String sourceName, String source, CompilationUnitTree unit, SourcePositions positions) {
            String typeName = type.getSimpleName().toString();
            if (typeName.isBlank()) {
                typeName = "<anonymous>";
            }
            long start = positions.getStartPosition(unit, type);
            long end = positions.getEndPosition(unit, type);
            int lineCount = lineCount(source, unit, start, end);
            List<String> launderingNames = methods.stream()
                    .filter(MethodShape::publicOrProtected)
                    .map(MethodShape::name)
                    .filter(TypeBuilder::isVisibilityLaunderingName)
                    .toList();
            return new SourceShape(
                    sourceName,
                    typeName,
                    lineCount,
                    methods.size(),
                    nestedTypeCount,
                    launderingNames);
        }

        private static int lineCount(
                String source, CompilationUnitTree unit, long start, long end) {
            if (start < 0 || end < 0 || unit.getLineMap() == null) {
                return (int) source.lines().count();
            }
            return (int) (unit.getLineMap().getLineNumber(end)
                    - unit.getLineMap().getLineNumber(start) + 1);
        }

        private static boolean isVisibilityLaunderingName(String name) {
            String normalized = name.toLowerCase(Locale.ROOT);
            return VISIBILITY_LAUNDERING_NAMES.stream().anyMatch(normalized::contains);
        }
    }

    private record MethodShape(String name, boolean publicOrProtected) {

        private static MethodShape from(MethodTree method) {
            Set<Modifier> modifiers = method.getModifiers().getFlags();
            return new MethodShape(
                    method.getName().toString(),
                    modifiers.contains(Modifier.PUBLIC) || modifiers.contains(Modifier.PROTECTED));
        }
    }

    private record SourceShape(
            String sourceName,
            String typeName,
            int lineCount,
            int methodCount,
            int nestedTypeCount,
            List<String> visibilityLaunderingNames) {

        private static List<SourceShape> from(Path sourceFile) throws IOException {
            return parseSource(sourceFile.toString(), Files.readString(sourceFile));
        }

        private static SourceShape from(String sourceName, String source) {
            List<SourceShape> shapes = parseSource(sourceName, source);
            if (shapes.size() != 1) {
                throw new IllegalStateException(
                        "fixture must contain one top-level type: " + sourceName + " -> "
                                + shapes.stream().map(SourceShape::typeName).toList());
            }
            return shapes.getFirst();
        }

        private List<String> violations() {
            List<String> violations = new ArrayList<>();
            if (lineCount > MAX_CLASS_LINES) {
                violations.add("class length: " + lineCount);
            }
            if (methodCount > MAX_METHODS) {
                violations.add("method count: " + methodCount);
            }
            if (nestedTypeCount > 0) {
                violations.add("nested workflow types: " + nestedTypeCount);
            }
            if (hasGenericOwnerName(sourceName, typeName)) {
                violations.add("generic owner name: " + typeName);
            }
            for (String name : visibilityLaunderingNames) {
                violations.add("visibility laundering: " + name);
            }
            return List.copyOf(violations);
        }

        private static boolean hasGenericOwnerName(String sourceName, String typeName) {
            String normalizedPath = sourceName.replace('\\', '/').toLowerCase(Locale.ROOT);
            for (String segment : normalizedPath.split("[/.]")) {
                if (GENERIC_OWNER_NAMES.contains(segment)) {
                    return true;
                }
            }
            String normalizedType = typeName.toLowerCase(Locale.ROOT);
            return GENERIC_OWNER_NAMES.stream().anyMatch(normalizedType::endsWith);
        }
    }
}
