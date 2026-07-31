package com.nimbly.mcpjavadevtools.requestmapping;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.nimbly.mcpjavadevtools.requestmapping.api.FailureResponse;
import com.nimbly.mcpjavadevtools.requestmapping.api.RequestCandidate;
import com.nimbly.mcpjavadevtools.requestmapping.api.ResolverRequest;
import com.nimbly.mcpjavadevtools.requestmapping.api.ResolverResponse;
import com.nimbly.mcpjavadevtools.requestmapping.api.SuccessResponse;
import com.nimbly.mcpjavadevtools.requestmapping.ast.MethodContext;
import com.nimbly.mcpjavadevtools.requestmapping.ast.TypeDescriptor;
import com.nimbly.mcpjavadevtools.requestmapping.core.BytecodeSpringMappingFallback;
import com.nimbly.mcpjavadevtools.requestmapping.core.BytecodeResolvedEndpoint;
import com.nimbly.mcpjavadevtools.requestmapping.core.HandlerInventoryResolver;
import com.nimbly.mcpjavadevtools.requestmapping.core.MethodSelector;
import com.nimbly.mcpjavadevtools.requestmapping.core.TypeIndex;
import com.nimbly.mcpjavadevtools.requestmapping.core.TypeIndexBuilder;
import com.nimbly.mcpjavadevtools.requestmapping.extractor.ExtractorRegistry;
import com.nimbly.mcpjavadevtools.requestmapping.extractor.MappingExtractor;
import com.nimbly.mcpjavadevtools.requestmapping.resolution.ResolvedMapping;
import com.nimbly.mcpjavadevtools.requestmapping.transport.http.ParameterTemplateBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RequestMappingResolver {
    private static final String CONTRACT_VERSION = ContractVersion.value();
    private static final String BYTECODE_FALLBACK_STRATEGY = "java_bytecode_spring_annotation_fallback";
    private static final List<String> BOOTSTRAP_STRATEGIES = List.of(
            "java_ast_index_lookup",
            "java_ast_framework_resolution"
    );
    private final ExtractorRegistry extractorRegistry;

    public RequestMappingResolver() {
        this(ExtractorRegistry.serviceLoaderDefault());
    }

    public RequestMappingResolver(ExtractorRegistry extractorRegistry) {
        this.extractorRegistry = extractorRegistry;
    }

    public ResolverResponse resolve(ResolverRequest request) {
        if (request == null || request.projectRootAbs == null || request.projectRootAbs.isBlank()) {
            return failure(
                    "project_root_invalid",
                    "project_root_validation",
                    "Provide projectRootAbs as an absolute existing project directory path.",
                    List.of("projectRootAbs missing or blank"),
                    BOOTSTRAP_STRATEGIES
            );
        }

        Path projectRoot = Paths.get(request.projectRootAbs).toAbsolutePath().normalize();
        if (!projectRoot.isAbsolute() || !Files.isDirectory(projectRoot)) {
            return failure(
                    "project_root_invalid",
                    "project_root_validation",
                    "Provide projectRootAbs as an absolute existing project directory path.",
                    List.of("projectRootAbs is not an existing directory", "projectRootAbs=" + projectRoot),
                    BOOTSTRAP_STRATEGIES
            );
        }
        ParameterTemplateBuilder.configureForProjectRoot(projectRoot);
        String templateProfileSource = ParameterTemplateBuilder.activeProfileSource();

        List<Path> scanRoots = new ArrayList<>();
        scanRoots.add(projectRoot);
        if (request.searchRootsAbs != null) {
            for (String searchRoot : request.searchRootsAbs) {
                if (searchRoot == null || searchRoot.isBlank()) {
                    continue;
                }
                Path candidate = Paths.get(searchRoot).toAbsolutePath().normalize();
                if (Files.isDirectory(candidate) && !scanRoots.contains(candidate)) {
                    scanRoots.add(candidate);
                }
            }
        }

        TypeIndex index = TypeIndexBuilder.build(scanRoots);
        if (index.getTypeCount() == 0) {
            return failure(
                    "target_type_not_found",
                    "target_type_resolution",
                    "No Java source types were indexed under the provided project root.",
                    List.of("indexedJavaFiles=0", "projectRootAbs=" + projectRoot),
                    BOOTSTRAP_STRATEGIES
            );
        }

        if ("discover_handlers".equals(request.action)) {
            return HandlerInventoryResolver.resolve(
                    request, projectRoot, index, extractorRegistry, CONTRACT_VERSION);
        }

        TypeDescriptor primaryType = MethodSelector.selectPrimaryType(index, request);
        if (primaryType == null) {
            List<TypeDescriptor> candidates = index.lookupTypes(request.classHint);
            int candidateCount = candidates.size();
            String reason = candidateCount > 1 ? "target_type_ambiguous" : "target_type_not_found";
            String nextAction = candidateCount > 1
                    ? "Narrow projectRootAbs to a single runtime module or provide inferredTargetFileAbs that matches classHint exactly, then rerun request mapping resolution."
                    : "Refine classHint to an exact runtime FQCN and rerun request mapping resolution.";
            List<String> candidateFiles = candidates.stream()
                    .map(descriptor -> descriptor.getFileAbs().toString())
                    .sorted()
                    .limit(8)
                    .toList();
            return failure(
                    reason,
                    "target_type_resolution",
                    nextAction,
                    List.of(
                            "classHint=" + safe(request.classHint),
                            "typeCandidates=" + candidateCount,
                            "indexedJavaFiles=" + index.getTypeCount(),
                            "candidateFiles=" + String.join(",", candidateFiles)
                    ),
                    BOOTSTRAP_STRATEGIES
            );
        }

        MethodDeclaration primaryMethod = MethodSelector.findMethod(primaryType, request.methodHint, request.lineHint, -1);
        List<MethodContext> methodContexts;
        if (primaryMethod != null) {
            methodContexts = MethodSelector.collectMethodContexts(primaryType, primaryMethod, index);
        } else {
            methodContexts = MethodSelector.collectMethodContextsByHint(
                    primaryType,
                    request.methodHint,
                    request.lineHint,
                    index
            );
        }

        if (methodContexts.isEmpty()) {
            Optional<BytecodeResolvedEndpoint> bytecodeResolved =
                    BytecodeSpringMappingFallback.resolve(request, scanRoots, primaryType.getFqcn());
            if (bytecodeResolved.isPresent()) {
                return successFromBytecodeFallback(
                        projectRoot,
                        primaryType,
                        request,
                        bytecodeResolved.get(),
                        templateProfileSource
                );
            }
            return failure(
                    "target_method_not_found",
                    "target_method_resolution",
                    "Refine methodHint or lineHint and rerun request mapping resolution.",
                    List.of(
                            "classHint=" + safe(request.classHint),
                            "resolvedType=" + primaryType.getFqcn(),
                            "methodHint=" + safe(request.methodHint),
                            "bytecodeFallbackAttempted=true"
                    ),
                    List.of("java_ast_index_lookup", BYTECODE_FALLBACK_STRATEGY)
            );
        }

        List<MappingExtractor> extractors = extractorRegistry.listExtractors();
        if (extractors.isEmpty()) {
            return failure(
                    "mapper_plugin_unavailable",
                    "extractor_plugin_discovery",
                    "No mapping extractor plugin is loaded. Include adapter-request-mapper-spring-http on resolver classpath or build spring/all bundles and rerun request mapping resolution.",
                    List.of(
                            "loadedExtractors=0",
                            "classHint=" + safe(request.classHint),
                            "methodHint=" + safe(request.methodHint)
                    ),
                    List.of("java_ast_index_lookup", "service_loader_plugin_discovery")
            );
        }

        for (MethodContext context : methodContexts) {
            for (MappingExtractor extractor : extractors) {
                Optional<ResolvedMapping> resolved = extractor.resolve(context, index);
                if (resolved.isPresent()) {
                    return success(
                            request,
                            projectRoot,
                            primaryType,
                            context,
                            extractor.strategyId(),
                            resolved.get(),
                            templateProfileSource
                    );
                }
            }
        }

        Optional<BytecodeResolvedEndpoint> bytecodeResolved =
                BytecodeSpringMappingFallback.resolve(request, scanRoots, primaryType.getFqcn());
        if (bytecodeResolved.isPresent()) {
            return successFromBytecodeFallback(
                    projectRoot,
                    primaryType,
                    request,
                    bytecodeResolved.get(),
                    templateProfileSource
            );
        }

        return failure(
                "request_mapping_not_proven",
                "request_mapping_resolution",
                "AST resolver could not prove an HTTP entrypoint for the requested method. Refine classHint/methodHint/lineHint and rerun.",
                List.of(
                        "classHint=" + safe(request.classHint),
                        "resolvedType=" + primaryType.getFqcn(),
                        "methodHint=" + safe(request.methodHint),
                        "methodContextCount=" + methodContexts.size(),
                        "bytecodeFallbackAttempted=true"
                ),
                List.of("java_ast_index_lookup", "java_ast_framework_resolution", BYTECODE_FALLBACK_STRATEGY)
        );
    }

    private static SuccessResponse success(
            ResolverRequest request,
            Path projectRoot,
            TypeDescriptor primaryType,
            MethodContext context,
            String strategyId,
            ResolvedMapping mapping,
            String templateProfileSource
    ) {
        SuccessResponse response = new SuccessResponse();
        response.status = "ok";
        response.contractVersion = CONTRACT_VERSION;
        response.framework = mapping.getFramework();
        response.requestSource = mapping.getRequestSource();
        response.requestCandidate = buildRequestCandidate(mapping, context, templateProfileSource);
        response.matchedTypeFile = primaryType.getFileAbs().toString();
        response.matchedRootAbs = projectRoot.toString();
        response.evidence = List.of(
                "resolvedType=" + primaryType.getFqcn(),
                "mappingOwner=" + context.owner().getFqcn(),
                "methodHint=" + request.methodHint,
                "framework=" + mapping.getFramework(),
                "templateProfile=" + templateProfileSource
        );
        response.attemptedStrategies = List.of("java_ast_index_lookup", strategyId);
        if (mapping.getExtensions() != null && !mapping.getExtensions().isEmpty()) {
            response.extensions = mapping.getExtensions();
        }
        return response;
    }

    private static RequestCandidate buildRequestCandidate(
            ResolvedMapping mapping,
            MethodContext context,
            String templateProfileSource
    ) {
        RequestCandidate candidate = new RequestCandidate();
        candidate.method = mapping.getHttpMethod();
        candidate.path = mapping.getMaterializedPath();
        candidate.queryTemplate = mapping.getQueryTemplate();
        candidate.fullUrlHint = mapping.getQueryTemplate().isBlank()
                ? mapping.getMaterializedPath()
                : mapping.getMaterializedPath() + "?" + mapping.getQueryTemplate();
        if (mapping.getBodyTemplate() != null && !mapping.getBodyTemplate().isBlank()) {
            candidate.bodyTemplate = mapping.getBodyTemplate();
        }
        List<String> rationale = new ArrayList<>(List.of(
                "Resolved HTTP mapping from Java AST.",
                "Mapping owner: " + context.owner().getFqcn(),
                "Framework resolver: " + mapping.getFramework(),
                "Request template profile: " + templateProfileSource
        ));
        if (!mapping.getPathParameters().isEmpty()) {
            rationale.add("Materialized path params: " + String.join(", ", mapping.getPathParameters()));
        }
        candidate.rationale = rationale;

        if (usesFallbackTemplateSample(candidate)) {
            candidate.assumptions = List.of(
                    "Request template contains built-in fallback sample values and may not match runtime fixture constraints."
            );
            candidate.needsConfirmation = List.of(
                    "Provide project fixture overrides in .mcp-java-dev-tools/request-template.properties for stable endpoint assertions."
            );
        }
        return candidate;
    }

    private static SuccessResponse successFromBytecodeFallback(
            Path projectRoot,
            TypeDescriptor primaryType,
            ResolverRequest request,
            BytecodeResolvedEndpoint endpoint,
            String templateProfileSource
    ) {
        SuccessResponse response = new SuccessResponse();
        response.status = "ok";
        response.contractVersion = CONTRACT_VERSION;
        response.framework = "spring_mvc";
        response.requestSource = "spring_mvc";
        response.requestCandidate = buildBytecodeFallbackRequestCandidate(endpoint, templateProfileSource);
        response.matchedTypeFile = primaryType.getFileAbs().toString();
        response.matchedRootAbs = projectRoot.toString();
        response.evidence = List.of(
                "resolvedType=" + primaryType.getFqcn(),
                "mappingOwner=" + endpoint.mappingOwnerFqcn(),
                "methodHint=" + request.methodHint,
                "mapping_source=bytecode_annotation",
                "templateProfile=" + templateProfileSource
        );
        response.attemptedStrategies = List.of("java_ast_index_lookup", BYTECODE_FALLBACK_STRATEGY);
        return response;
    }

    private static RequestCandidate buildBytecodeFallbackRequestCandidate(
            BytecodeResolvedEndpoint endpoint,
            String templateProfileSource
    ) {
        RequestCandidate candidate = new RequestCandidate();
        candidate.method = endpoint.httpMethod();
        candidate.path = endpoint.path();
        candidate.queryTemplate = "";
        candidate.fullUrlHint = endpoint.path();
        candidate.rationale = List.of(
                "Resolved HTTP mapping from compiled bytecode annotations.",
                "Mapping owner: " + endpoint.mappingOwnerFqcn(),
                "Request template profile: " + templateProfileSource
        );
        return candidate;
    }

    private static boolean usesFallbackTemplateSample(RequestCandidate candidate) {
        String fallbackQuery = ParameterTemplateBuilder.fallbackQuerySample();
        String fallbackBody = ParameterTemplateBuilder.fallbackBodySample();
        boolean hasFallbackQuery = candidate.queryTemplate != null
                && !candidate.queryTemplate.isBlank()
                && candidate.queryTemplate.contains("=" + fallbackQuery);
        boolean hasFallbackBody = candidate.bodyTemplate != null
                && candidate.bodyTemplate.equals(fallbackBody);
        return hasFallbackQuery || hasFallbackBody;
    }

    private static FailureResponse failure(
            String reasonCode,
            String failedStep,
            String nextAction,
            List<String> evidence,
            List<String> attemptedStrategies
    ) {
        FailureResponse response = new FailureResponse();
        response.status = "report";
        response.contractVersion = CONTRACT_VERSION;
        response.reasonCode = reasonCode;
        response.failedStep = failedStep;
        response.nextAction = nextAction;
        response.evidence = evidence;
        response.attemptedStrategies = attemptedStrategies;
        return response;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }
}

