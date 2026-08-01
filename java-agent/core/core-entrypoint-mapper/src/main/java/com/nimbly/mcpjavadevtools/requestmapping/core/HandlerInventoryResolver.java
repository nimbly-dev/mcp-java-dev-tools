package com.nimbly.mcpjavadevtools.requestmapping.core;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.nimbly.mcpjavadevtools.requestmapping.api.HandlerInventoryEntry;
import com.nimbly.mcpjavadevtools.requestmapping.api.HandlerInventoryResponse;
import com.nimbly.mcpjavadevtools.requestmapping.api.FailureResponse;
import com.nimbly.mcpjavadevtools.requestmapping.api.ResolverRequest;
import com.nimbly.mcpjavadevtools.requestmapping.api.ResolverResponse;
import com.nimbly.mcpjavadevtools.requestmapping.api.MethodContext;
import com.nimbly.mcpjavadevtools.requestmapping.api.TypeDescriptor;
import com.nimbly.mcpjavadevtools.requestmapping.extractor.ExtractorRegistry;
import com.nimbly.mcpjavadevtools.requestmapping.api.MappingExtractor;
import com.nimbly.mcpjavadevtools.requestmapping.api.ResolvedMapping;
import com.nimbly.mcpjavadevtools.requestmapping.api.TypeIndex;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class HandlerInventoryResolver {
    private final String contractVersion;

    private HandlerInventoryResolver(String contractVersion) {
        this.contractVersion = contractVersion;
    }

    public static ResolverResponse resolve(
            ResolverRequest request,
            Path projectRoot,
            TypeIndex index,
            ExtractorRegistry extractorRegistry,
            String contractVersion
    ) {
        return new HandlerInventoryResolver(contractVersion).resolveInventory(
                request, projectRoot, index, extractorRegistry);
    }

    private ResolverResponse resolveInventory(
            ResolverRequest request,
            Path projectRoot,
            TypeIndex index,
            ExtractorRegistry extractorRegistry
    ) {
        TypeDescriptor controller = MethodSelector.selectPrimaryType(index, request);
        if (controller == null) {
            return failureForController(index, request);
        }
        List<MappingExtractor> extractors = extractorRegistry.listExtractors();
        if (extractors.isEmpty()) {
            return failure("mapper_plugin_unavailable", "extractor_plugin_discovery",
                    "Load the Spring request mapper and rerun handler discovery.",
                    List.of("loadedExtractors=0"));
        }
        List<HandlerInventoryEntry> handlers = discoverHandlers(controller, index, extractors);
        if (handlers.isEmpty()) {
            return failure("controller_handlers_not_found", "handler_discovery",
                    "Provide an exact Spring controller FQCN with supported HTTP handler mappings.",
                    List.of("resolvedType=" + controller.getFqcn(), "supportedHandlers=0"));
        }
        return success(projectRoot, controller, handlers);
    }

    private ResolverResponse failureForController(TypeIndex index, ResolverRequest request) {
        List<TypeDescriptor> candidates = index.lookupTypes(request.classHint);
        String reasonCode = candidates.size() > 1 ? "target_type_ambiguous" : "target_type_not_found";
        return failure(reasonCode, "target_type_resolution",
                "Provide an exact controller FQCN and rerun handler discovery.",
                List.of("classHint=" + safe(request.classHint), "typeCandidates=" + candidates.size()));
    }

    private static List<HandlerInventoryEntry> discoverHandlers(
            TypeDescriptor controller,
            TypeIndex index,
            List<MappingExtractor> extractors
    ) {
        List<HandlerInventoryEntry> handlers = new ArrayList<>();
        for (MethodContext candidate : collectCandidateMethods(controller, index)) {
            resolveHandler(candidate, index, extractors).ifPresent(handlers::add);
        }
        handlers.sort(Comparator.comparingInt(entry -> entry.declarationLine));
        return handlers;
    }

    private static List<MethodContext> collectCandidateMethods(
            TypeDescriptor controller,
            TypeIndex index
    ) {
        List<MethodContext> candidates = new ArrayList<>();
        Set<String> visitedTypes = new HashSet<>();
        Set<String> visitedMethods = new HashSet<>();
        Deque<TypeDescriptor> queue = new ArrayDeque<>();
        queue.add(controller);

        while (!queue.isEmpty()) {
            TypeDescriptor current = queue.removeFirst();
            if (!visitedTypes.add(current.getFqcn())) continue;
            for (MethodDeclaration method : current.getTypeDeclaration().getMethods()) {
                if (visitedMethods.add(method.getSignature().asString())) {
                    candidates.add(new MethodContext(current, method, controller));
                }
            }
            queue.addAll(resolveParents(current, index));
        }
        return candidates;
    }

    private static List<TypeDescriptor> resolveParents(TypeDescriptor type, TypeIndex index) {
        if (!(type.getTypeDeclaration() instanceof ClassOrInterfaceDeclaration declaration)) {
            return List.of();
        }
        List<TypeDescriptor> parents = new ArrayList<>();
        List<ClassOrInterfaceType> parentTypes = new ArrayList<>();
        parentTypes.addAll(declaration.getExtendedTypes());
        parentTypes.addAll(declaration.getImplementedTypes());
        for (ClassOrInterfaceType parentType : parentTypes) {
            TypeDescriptor parent = index.resolveTypeReference(type, parentType.getNameAsString());
            if (parent != null) parents.add(parent);
        }
        return parents;
    }

    private static Optional<HandlerInventoryEntry> resolveHandler(
            MethodContext candidate,
            TypeIndex index,
            List<MappingExtractor> extractors
    ) {
        List<MethodContext> contexts = MethodSelector.collectMethodContexts(
                candidate.owner(), candidate.method(), index);
        for (MethodContext context : contexts) {
            for (MappingExtractor extractor : extractors) {
                Optional<ResolvedMapping> resolved = extractor.resolve(context, index);
                if (resolved.isPresent()) {
                    return Optional.of(toEntry(candidate, resolved.get()));
                }
            }
        }
        return Optional.empty();
    }

    private static HandlerInventoryEntry toEntry(MethodContext context, ResolvedMapping mapping) {
        MethodDeclaration method = context.method();
        HandlerInventoryEntry entry = new HandlerInventoryEntry();
        entry.httpMethod = mapping.getHttpMethod();
        entry.path = mapping.getMaterializedPath();
        entry.methodName = method.getNameAsString();
        entry.signature = method.getDeclarationAsString(false, false, false);
        entry.runtimeClassFqcn = context.owner().getFqcn();
        entry.declarationLine = method.getBegin().map(position -> position.line).orElse(1);
        entry.endLine = method.getEnd().map(position -> position.line).orElse(entry.declarationLine);
        return entry;
    }

    private HandlerInventoryResponse success(
            Path projectRoot,
            TypeDescriptor controller,
            List<HandlerInventoryEntry> handlers
    ) {
        HandlerInventoryResponse response = new HandlerInventoryResponse();
        response.status = "ok";
        response.contractVersion = contractVersion;
        response.framework = "spring_http";
        response.controllerFqcn = controller.getFqcn();
        response.matchedTypeFile = controller.getFileAbs().toString();
        response.matchedRootAbs = projectRoot.toString();
        response.handlers = handlers;
        response.evidence = List.of("resolvedType=" + controller.getFqcn(), "handlerCount=" + handlers.size());
        response.attemptedStrategies = List.of("java_ast_index_lookup", "java_ast_framework_resolution");
        return response;
    }

    private ResolverResponse failure(
            String reasonCode,
            String failedStep,
            String nextAction,
            List<String> evidence
    ) {
        FailureResponse response = new FailureResponse();
        response.status = "report";
        response.contractVersion = contractVersion;
        response.reasonCode = reasonCode;
        response.failedStep = failedStep;
        response.nextAction = nextAction;
        response.evidence = evidence;
        response.attemptedStrategies = List.of("java_ast_index_lookup", "java_ast_framework_resolution");
        return response;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }
}
