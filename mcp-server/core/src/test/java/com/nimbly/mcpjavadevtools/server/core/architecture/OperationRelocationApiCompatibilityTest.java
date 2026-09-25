package com.nimbly.mcpjavadevtools.server.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericDeclaration;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Compares the flat #610 JVM API with the #613 relocated JVM API. */
class OperationRelocationApiCompatibilityTest {

    private static final String CORE_PACKAGE =
            "com.nimbly.mcpjavadevtools.server.core.";
    private static final String BASELINE_OPERATION_PACKAGE = CORE_PACKAGE + "operation";
    private static final String BASELINE_RESOURCE =
            "/mcpjvm-613/baseline-operation-api.resolved.txt";
    private static final Set<String> INTENTIONALLY_REMOVED_TYPES = Set.of(
            BASELINE_OPERATION_PACKAGE + ".OperationLegacyIdentity");
    private static final Set<String> INTENTIONALLY_REPLACED_API_TYPES = Set.of(
            BASELINE_OPERATION_PACKAGE + ".Operation",
            BASELINE_OPERATION_PACKAGE + ".OperationRegistration",
            BASELINE_OPERATION_PACKAGE + ".OperationInvocation",
            BASELINE_OPERATION_PACKAGE + ".OperationCatalogEntry",
            BASELINE_OPERATION_PACKAGE + ".OperationDocumentation",
            BASELINE_OPERATION_PACKAGE + ".OperationExecutionStatus");

    @Test
    void baselineOperationTypesKeepTheirResolvedApiAfterRelocation() throws Exception {
        Path repository = repositoryRoot();
        Map<String, Set<String>> baseline = baselineApi();
        Set<String> retainedTypes = baseline.keySet().stream()
                .filter(type -> !INTENTIONALLY_REMOVED_TYPES.contains(type))
                .collect(Collectors.toUnmodifiableSet());
        Map<String, String> typeIdentities = approvedTypeIdentities(retainedTypes);

        assertThat(baseline).hasSize(74);
        assertThat(Files.exists(repository.resolve(
                "mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/operation/trace/"
                        + "OperationLegacyIdentity.java"))).isFalse();
        for (Map.Entry<String, Set<String>> entry : baseline.entrySet()) {
            String baselineType = entry.getKey();
            if (INTENTIONALLY_REMOVED_TYPES.contains(baselineType)
                    || INTENTIONALLY_REPLACED_API_TYPES.contains(baselineType)) {
                continue;
            }
            assertThat(entry.getValue())
                    .as("resolved API fixture for %s", baselineType)
                    .anyMatch(signature -> signature.startsWith("TYPE|"));

            Set<String> expected = normalizeApi(entry.getValue(), typeIdentities);
            for (String candidateType : candidateTypeNames(baselineType)) {
                Path candidateSource = sourcePath(repository, candidateType);
                assertThat(Files.isRegularFile(candidateSource))
                        .as("candidate source for %s -> %s", baselineType, candidateType)
                        .isTrue();
                Set<String> actual = resolvedApi(Class.forName(candidateType), typeIdentities);
                Set<String> allowed616Additions = intentional616Additions(candidateType);
                Set<String> expectedWith616Additions = new TreeSet<>(expected);
                expectedWith616Additions.addAll(allowed616Additions);
                assertThat(actual)
                        .as("resolved JVM API for %s -> %s", baselineType, candidateType)
                        .containsExactlyInAnyOrderElementsOf(expectedWith616Additions);
            }
        }
    }

    private Set<String> intentional616Additions(String candidateType) {
        if (candidateType.endsWith(".OperationDirectory")) {
            String owner = BASELINE_OPERATION_PACKAGE + ".OperationDirectory";
            String packagePath = BASELINE_OPERATION_PACKAGE.replace('.', '/');
            return Set.of("METHOD|" + owner + "|public static|typeParameters=[]|return="
                    + owner + "|name=strict|params=[java.util.List<? extends "
                    + BASELINE_OPERATION_PACKAGE + ".OperationRegistration<?, ?>>, "
                    + BASELINE_OPERATION_PACKAGE + ".OperationManifestDocument, "
                    + "com.fasterxml.jackson.databind.ObjectMapper]|throws=[]|descriptor=(Ljava/util/List;L"
                    + packagePath + "/OperationManifestDocument;Lcom/fasterxml/jackson/databind/"
                    + "ObjectMapper;)L" + packagePath + "/OperationDirectory;|varargs=false");
        }
        Set<String> consolidationAdditions = intentional626Additions(candidateType);
        if (!consolidationAdditions.isEmpty()) {
            return consolidationAdditions;
        }
        String assembler = BASELINE_OPERATION_PACKAGE + ".OperationManifestAssembler";
        String execution = BASELINE_OPERATION_PACKAGE + ".OperationInvocationExecution";
        String descriptorPackage = BASELINE_OPERATION_PACKAGE.replace('.', '/');
        if (candidateType.endsWith(".OperationManifestAssembler")) {
            return Set.of("METHOD|" + assembler + "|public static|typeParameters=[]|return="
                    + BASELINE_OPERATION_PACKAGE + ".OperationManifest|name=assembleStrict|params=["
                    + "java.util.List<? extends " + BASELINE_OPERATION_PACKAGE
                    + ".OperationRegistration<?, ?>>, " + assembler.substring(0,
                    assembler.lastIndexOf('.') + 1) + "OperationManifestDocument]|throws=[]|descriptor=(Ljava/util/List;L"
                    + descriptorPackage + "/OperationManifestDocument;)L" + descriptorPackage
                    + "/OperationManifest;|varargs=false");
        }
        if (candidateType.endsWith(".OperationInvocationExecution")) {
            return Set.of("METHOD|" + execution + "|public static|typeParameters=[]|return="
                    + BASELINE_OPERATION_PACKAGE + ".OperationExecutionResult|name=run|params=["
                    + BASELINE_OPERATION_PACKAGE + ".OperationManifest, "
                    + "com.fasterxml.jackson.databind.ObjectMapper, " + BASELINE_OPERATION_PACKAGE
                    + ".OperationId, com.fasterxml.jackson.databind.JsonNode, boolean]|throws=[]|descriptor=(L"
                    + descriptorPackage + "/OperationManifest;Lcom/fasterxml/jackson/databind/ObjectMapper;L"
                    + descriptorPackage + "/OperationId;Lcom/fasterxml/jackson/databind/JsonNode;Z)L"
                    + descriptorPackage + "/OperationExecutionResult;|varargs=false");
        }
        return Set.of();
    }

    private Set<String> intentional626Additions(String candidateType) {
        String budget = BASELINE_OPERATION_PACKAGE + ".schema.OperationValidationBudget";
        String json = "com.fasterxml.jackson.databind.JsonNode";
        String owner;
        String result;
        String method;
        String parameters;
        String descriptor;
        if (candidateType.endsWith(".OperationSchemaValidator")) {
            owner = BASELINE_OPERATION_PACKAGE + ".OperationSchemaValidator";
            result = "java.util.List<java.lang.String>";
            method = "violations";
            parameters = BASELINE_OPERATION_PACKAGE + ".OperationSchema, " + json + ", " + budget;
            descriptor = "(L" + BASELINE_OPERATION_PACKAGE.replace('.', '/')
                    + "/OperationSchema;Lcom/fasterxml/jackson/databind/JsonNode;L"
                    + budget.replace('.', '/') + ";)Ljava/util/List;";
        } else if (candidateType.endsWith(".OperationValueRedactor")) {
            owner = BASELINE_OPERATION_PACKAGE + ".OperationValueRedactor";
            result = json;
            method = "redact";
            parameters = json + ", java.lang.String, " + budget;
            descriptor = "(Lcom/fasterxml/jackson/databind/JsonNode;Ljava/lang/String;L"
                    + budget.replace('.', '/') + ";)Lcom/fasterxml/jackson/databind/JsonNode;";
        } else {
            return Set.of();
        }
        return Set.of("METHOD|" + owner + "|public static|typeParameters=[]|return=" + result
                + "|name=" + method + "|params=[" + parameters + "]|throws=[]|descriptor="
                + descriptor + "|varargs=false");
    }

    @Test
    void resolvedApiComparisonDetectsRecordAndImportedTypeChanges() {
        Map<String, String> noRelocations = Map.of();
        Set<String> stringRecord = withoutOwner(
                StringRecord.class, resolvedApi(StringRecord.class, noRelocations));
        Set<String> integerRecord = withoutOwner(
                IntegerRecord.class, resolvedApi(IntegerRecord.class, noRelocations));
        Set<String> utilDate = withoutOwner(
                UtilDateApi.class, resolvedApi(UtilDateApi.class, noRelocations));
        Set<String> sqlDate = withoutOwner(
                SqlDateApi.class, resolvedApi(SqlDateApi.class, noRelocations));
        Set<String> unboundedGeneric = withoutOwner(
                UnboundedGenericApi.class, resolvedApi(UnboundedGenericApi.class, noRelocations));
        Set<String> boundedGeneric = withoutOwner(
                SerializableGenericApi.class, resolvedApi(SerializableGenericApi.class, noRelocations));
        Set<String> leftThenRight = withoutOwner(
                LeftThenRight.class, resolvedApi(LeftThenRight.class, noRelocations));
        Set<String> rightThenLeft = withoutOwner(
                RightThenLeft.class, resolvedApi(RightThenLeft.class, noRelocations));

        assertThat(integerRecord).isNotEqualTo(stringRecord);
        assertThat(sqlDate).isNotEqualTo(utilDate);
        assertThat(boundedGeneric).isNotEqualTo(unboundedGeneric);
        assertThat(rightThenLeft).isNotEqualTo(leftThenRight);
    }

    private Map<String, Set<String>> baselineApi() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(BASELINE_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("resolved baseline API fixture is missing");
            }
            Map<String, Set<String>> values = new TreeMap<>();
            for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines().toList()) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\\|", -1);
                if (fields.length < 2 || !isApiKind(fields[0]) || fields[1].isBlank()) {
                    throw new IllegalStateException("malformed resolved API fixture line: " + line);
                }
                if (!fields[1].startsWith(BASELINE_OPERATION_PACKAGE + ".")) {
                    throw new IllegalStateException("unexpected baseline API type: " + fields[1]);
                }
                values.computeIfAbsent(fields[1], ignored -> new TreeSet<>()).add(line);
            }
            return values.entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(
                            Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        }
    }

    private boolean isApiKind(String kind) {
        return Set.of("TYPE", "RECORD_COMPONENT", "CONSTRUCTOR", "METHOD", "FIELD")
                .contains(kind);
    }

    private Map<String, String> approvedTypeIdentities(Set<String> baselineTypes) {
        Map<String, String> identities = new TreeMap<>();
        for (String baselineType : new TreeSet<>(baselineTypes)) {
            identities.put(baselineType, baselineType);
            for (String candidate : candidateTypeNames(baselineType)) {
                identities.put(candidate, baselineType);
            }
        }
        return Map.copyOf(identities);
    }

    private Set<String> normalizeApi(Set<String> signatures, Map<String, String> identities) {
        return signatures.stream()
                .map(signature -> normalizeReferences(signature, identities))
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> resolvedApi(Class<?> type, Map<String, String> identities) {
        String owner = normalizeReferences(type.getName(), identities);
        Set<String> signatures = new TreeSet<>();
        signatures.add(typeSignature(type, owner, identities));
        addRecordComponents(type, owner, identities, signatures);
        addConstructors(type, owner, identities, signatures);
        addMethods(type, owner, identities, signatures);
        addFields(type, owner, identities, signatures);
        return Set.copyOf(signatures);
    }

    private String typeSignature(Class<?> type, String owner, Map<String, String> identities) {
        String interfaces = Arrays.stream(type.getGenericInterfaces())
                .map(Type::getTypeName)
                .sorted()
                .map(value -> normalizeReferences(value, identities))
                .toList()
                .toString();
        return "TYPE|" + owner + "|" + typeKind(type) + "|" + Modifier.toString(type.getModifiers())
                + "|typeParameters=" + typeParameters(type, identities)
                + "|super=" + normalizeReferences(typeName(type.getGenericSuperclass()), identities)
                + "|interfaces=" + interfaces;
    }

    private String typeKind(Class<?> type) {
        if (type.isRecord()) {
            return "record";
        }
        if (type.isInterface()) {
            return "interface";
        }
        if (type.isEnum()) {
            return "enum";
        }
        if (type.isAnnotation()) {
            return "annotation";
        }
        return "class";
    }

    private void addRecordComponents(
            Class<?> type, String owner, Map<String, String> identities, Set<String> signatures) {
        if (!type.isRecord()) {
            return;
        }
        RecordComponent[] components = type.getRecordComponents();
        for (int ordinal = 0; ordinal < components.length; ordinal++) {
            RecordComponent component = components[ordinal];
            String componentType = normalizeReferences(component.getGenericType().getTypeName(), identities);
            String accessorType = normalizeReferences(
                    component.getAccessor().getGenericReturnType().getTypeName(), identities);
            signatures.add("RECORD_COMPONENT|" + owner + "|ordinal=" + ordinal
                    + "|name=" + component.getName()
                    + "|type=" + componentType + "|accessor=" + accessorType);
        }
    }

    private void addConstructors(
            Class<?> type, String owner, Map<String, String> identities, Set<String> signatures) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (isExposed(constructor.getModifiers())) {
                signatures.add("CONSTRUCTOR|" + owner + "|" + Modifier.toString(constructor.getModifiers())
                        + "|typeParameters=" + typeParameters(constructor, identities)
                        + "|params=" + typeNames(constructor.getGenericParameterTypes(), identities)
                        + "|throws=" + typeNames(constructor.getGenericExceptionTypes(), identities)
                        + "|descriptor=" + constructorDescriptor(constructor, identities)
                        + "|varargs=" + constructor.isVarArgs());
            }
        }
    }

    private void addMethods(
            Class<?> type, String owner, Map<String, String> identities, Set<String> signatures) {
        for (Method method : type.getDeclaredMethods()) {
            if (isExposed(method.getModifiers())) {
                signatures.add("METHOD|" + owner + "|" + Modifier.toString(method.getModifiers())
                        + "|typeParameters=" + typeParameters(method, identities)
                        + "|return=" + normalizeReferences(method.getGenericReturnType().getTypeName(), identities)
                        + "|name=" + method.getName()
                        + "|params=" + typeNames(method.getGenericParameterTypes(), identities)
                        + "|throws=" + typeNames(method.getGenericExceptionTypes(), identities)
                        + "|descriptor=" + methodDescriptor(method, identities)
                        + "|varargs=" + method.isVarArgs());
            }
        }
    }

    private void addFields(
            Class<?> type, String owner, Map<String, String> identities, Set<String> signatures) {
        for (Field field : type.getDeclaredFields()) {
            if (isExposed(field.getModifiers())) {
                signatures.add("FIELD|" + owner + "|" + Modifier.toString(field.getModifiers())
                        + "|name=" + field.getName()
                        + "|type=" + normalizeReferences(field.getGenericType().getTypeName(), identities));
            }
        }
    }

    private String typeParameters(GenericDeclaration declaration, Map<String, String> identities) {
        return Arrays.stream(declaration.getTypeParameters())
                .map(variable -> variable.getName() + ":"
                        + Arrays.stream(variable.getBounds())
                        .map(Type::getTypeName)
                        .map(value -> normalizeReferences(value, identities))
                        .toList())
                .toList()
                .toString();
    }

    private String typeNames(Type[] types, Map<String, String> identities) {
        return Arrays.stream(types)
                .map(Type::getTypeName)
                .map(value -> normalizeReferences(value, identities))
                .toList()
                .toString();
    }

    private String constructorDescriptor(
            Constructor<?> constructor, Map<String, String> identities) {
        return "(" + Arrays.stream(constructor.getParameterTypes())
                .map(type -> descriptor(type, identities))
                .collect(Collectors.joining()) + ")V";
    }

    private String methodDescriptor(Method method, Map<String, String> identities) {
        return "(" + Arrays.stream(method.getParameterTypes())
                .map(type -> descriptor(type, identities))
                .collect(Collectors.joining()) + ")" + descriptor(method.getReturnType(), identities);
    }

    private String descriptor(Class<?> type, Map<String, String> identities) {
        if (type.isArray()) {
            return normalizeReferences(type.getName(), identities).replace('.', '/');
        }
        if (!type.isPrimitive()) {
            return "L" + normalizeReferences(type.getName(), identities).replace('.', '/') + ";";
        }
        return switch (type.getName()) {
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "double" -> "D";
            case "float" -> "F";
            case "int" -> "I";
            case "long" -> "J";
            case "short" -> "S";
            case "void" -> "V";
            default -> throw new IllegalArgumentException("unknown primitive type: " + type);
        };
    }

    private String typeName(Type type) {
        return type == null ? "<none>" : type.getTypeName();
    }

    private boolean isExposed(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private String normalizeReferences(String source, Map<String, String> identities) {
        String normalized = source;
        List<Map.Entry<String, String>> replacements = identities.entrySet().stream()
                .sorted(Map.Entry.<String, String>comparingByKey(
                        Comparator.comparingInt(String::length).reversed()))
                .toList();
        for (Map.Entry<String, String> replacement : replacements) {
            normalized = normalized.replace(replacement.getKey(), replacement.getValue());
        }
        return normalized;
    }

    private Set<String> withoutOwner(Class<?> type, Set<String> signatures) {
        return signatures.stream()
                .map(signature -> signature.replace(type.getName(), "@fixtureOwner@"))
                .collect(Collectors.toUnmodifiableSet());
    }

    private List<String> candidateTypeNames(String baselineType) {
        String simpleName = baselineType.substring(baselineType.lastIndexOf('.') + 1);
        if (simpleName.equals("SuiteOperationArguments")) {
            return List.of(
                    CORE_PACKAGE + "feature.suite.regression.model.operation.RegressionSuiteOperationArguments",
                    CORE_PACKAGE + "feature.suite.performance.model.operation.PerformanceSuiteOperationArguments",
                    CORE_PACKAGE + "feature.suite.security.model.operation.SecuritySuiteOperationArguments");
        }
        return List.of(
                CORE_PACKAGE + expectedCandidatePackage(simpleName + ".java").replace('/', '.')
                        + "." + simpleName);
    }

    private String expectedCandidatePackage(String fileName) {
        return switch (fileName) {
            case "CatalogPage.java", "CatalogQuery.java", "OperationDirectory.java",
                    "OperationExecutionResult.java", "OperationId.java", "OperationInvocation.java" ->
                    "operation";
            case "Operation.java", "OperationCatalog.java", "OperationCatalogEntry.java",
                    "OperationCatalogPageBuilder.java", "OperationCursor.java", "OperationExposure.java" ->
                    "operation/catalog";
            case "OperationExecutor.java", "OperationRegistration.java",
                    "OperationRegistrationBinding.java", "OperationRegistrationContract.java",
                    "OperationRequestDecoder.java", "OperationResultEncoder.java" ->
                    "operation/binding";
            case "CoreOperationDirectory.java", "CoreOperationDirectoryOwners.java" ->
                    "operation/composition";
            case "OperationDirectoryException.java", "OperationExecutionException.java",
                    "OperationExecutionStatus.java", "OperationInvocationExecution.java" ->
                    "operation/execution";
            case "OperationAlias.java", "OperationArgumentDocumentation.java", "OperationDescriptor.java",
                    "OperationDescriptorMetadata.java", "OperationDocumentation.java", "OperationManifest.java",
                    "OperationManifestAssembler.java", "OperationManifestDocument.java",
                    "OperationManifestLoader.java" -> "operation/manifest";
            case "OperationManifestXmlReader.java", "OperationManifestXmlStructureValidator.java" ->
                    "operation/manifest/xml";
            case "CanonicalOperationSchema.java", "CoreOperationResultFields.java",
                    "CoreOperationResultSchemas.java", "OperationSchema.java", "OperationSchemaRules.java",
                    "OperationSchemaValidator.java" -> "operation/schema";
            case "CoreOperationSafetyPolicy.java", "OperationSafetyPolicy.java", "OperationValueRedactor.java" ->
                    "operation/safety";
            case "OperationProvenance.java", "OperationProvenanceKind.java",
                    "OperationTraceEntry.java", "OperationTraceInventory.java",
                    "OperationTraceMetadata.java" -> "operation/trace";
            case "ArtifactOperationArguments.java" -> "feature/artifactmanagement/model/operation";
            case "ArtifactOperationRegistrations.java" -> "feature/artifactmanagement/operation";
            case "ExecutionOrchestrationArguments.java" ->
                    "feature/executionorchestration/model/operation";
            case "ExecutionOrchestrationOperationRegistrations.java" ->
                    "feature/executionorchestration/operation";
            case "ExecutionProfileExportArguments.java" ->
                    "feature/executionprofileexport/model/operation";
            case "ExecutionProfileExportOperationRegistrations.java" ->
                    "feature/executionprofileexport/operation";
            case "FailureAnalyzeArguments.java", "FailureExpectedFingerprintArguments.java",
                    "FailureInvestigationArguments.java", "FailureLineHitArguments.java",
                    "FailureTerminalArguments.java", "FailureVerifyArguments.java" ->
                    "feature/failureanalysis/model/operation";
            case "FailureAnalysisOperationRegistrations.java" -> "feature/failureanalysis/operation";
            case "JvmLifecycleOperationRegistrations.java" -> "feature/jvmlifecycle/operation";
            case "ProbeHttpArguments.java", "ProbeOperationArguments.java" ->
                    "feature/probe/model/operation";
            case "ProbeOperationRegistrations.java", "ProbeOperationSchemas.java" ->
                    "feature/probe/operation";
            case "RouteSynthesisOperationRegistrations.java" -> "feature/routesynthesis/operation";
            case "TransportExecuteArguments.java", "TransportExecuteOptionsArguments.java" ->
                    "feature/transportexecution/model/operation";
            case "TransportExecutionOperationRegistrations.java" ->
                    "feature/transportexecution/operation";
            case "RegressionSuiteOperationRegistrations.java" -> "feature/suite/regression/operation";
            case "PerformanceSuiteOperationRegistrations.java" -> "feature/suite/performance/operation";
            case "SecuritySuiteOperationRegistrations.java" -> "feature/suite/security/operation";
            default -> throw new IllegalStateException("unmapped baseline API type: " + fileName);
        };
    }

    private Path sourcePath(Path repository, String typeName) {
        String relative = typeName.replace('.', '/') + ".java";
        return repository.resolve("mcp-server/core/src/main/java").resolve(relative);
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve("mcp-server/core/src/main/java"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("repository root could not be located");
        }
        return current;
    }

    private record StringRecord(String value) {
    }

    private record IntegerRecord(Integer value) {
    }

    private static final class UtilDateApi {

        public java.util.Date value() {
            return null;
        }
    }

    private static final class SqlDateApi {

        public java.sql.Date value() {
            return null;
        }
    }

    private interface UnboundedGenericApi<T> {

        T execute(T input);
    }

    private interface SerializableGenericApi<T extends java.io.Serializable> {

        T execute(T input);
    }

    private record LeftThenRight(String left, String right) {
    }

    private record RightThenLeft(String right, String left) {
    }
}
