package com.nimbly.mcpjavadevtools.requestmapping.core;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.nimbly.mcpjavadevtools.requestmapping.api.ResolverRequest;
import com.nimbly.mcpjavadevtools.requestmapping.api.MethodContext;
import com.nimbly.mcpjavadevtools.requestmapping.api.TypeDescriptor;
import com.nimbly.mcpjavadevtools.requestmapping.api.TypeIndex;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MethodSelector {
    private MethodSelector() {
    }

    public static TypeDescriptor selectPrimaryType(TypeIndex index, ResolverRequest request) {
        if (request.inferredTargetFileAbs != null && !request.inferredTargetFileAbs.isBlank()) {
            Path inferredFile = Paths.get(request.inferredTargetFileAbs).toAbsolutePath().normalize();
            List<TypeDescriptor> descriptors = TypeIndexBuilder.parseTypeDescriptors(inferredFile);
            if (!descriptors.isEmpty()) {
                TypeDescriptor matched = selectDescriptorByClassHint(descriptors, request.classHint);
                if (matched != null) {
                    return matched;
                }
            }
        }

        List<TypeDescriptor> candidates = index.lookupTypes(request.classHint);
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        return null;
    }

    private static TypeDescriptor selectDescriptorByClassHint(List<TypeDescriptor> descriptors, String classHint) {
        if (classHint == null || classHint.isBlank()) {
            return null;
        }
        if (classHint.contains(".")) {
            for (TypeDescriptor descriptor : descriptors) {
                if (descriptor.getFqcn().equals(classHint)) {
                    return descriptor;
                }
            }
            return null;
        }
        for (TypeDescriptor descriptor : descriptors) {
            if (descriptor.getSimpleName().equals(classHint)) {
                return descriptor;
            }
        }
        return null;
    }

    public static MethodDeclaration findMethod(
            TypeDescriptor descriptor,
            String methodHint,
            Integer lineHint,
            int parameterCount
    ) {
        if (methodHint == null || methodHint.isBlank()) {
            return null;
        }
        List<MethodDeclaration> matches = descriptor.getTypeDeclaration().getMethodsByName(methodHint);
        if (parameterCount >= 0) {
            matches = matches.stream()
                    .filter(method -> method.getParameters().size() == parameterCount)
                    .toList();
        }
        if (matches.isEmpty()) {
            return null;
        }
        if (lineHint == null) {
            return matches.get(0);
        }
        return matches.stream()
                .min(Comparator.comparingInt(method -> {
                    int begin = method.getBegin().map(position -> position.line).orElse(lineHint);
                    int end = method.getEnd().map(position -> position.line).orElse(begin);
                    if (lineHint >= begin && lineHint <= end) {
                        return 0;
                    }
                    return Math.min(Math.abs(begin - lineHint), Math.abs(end - lineHint));
                }))
                .orElse(matches.get(0));
    }

    public static List<MethodContext> collectMethodContexts(
            TypeDescriptor primaryType,
            MethodDeclaration primaryMethod,
            TypeIndex index
    ) {
        List<MethodContext> out = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<MethodContext> queue = new ArrayDeque<>();
        queue.add(new MethodContext(primaryType, primaryMethod, primaryType));

        while (!queue.isEmpty()) {
            MethodContext current = queue.removeFirst();
            String visitKey = current.owner().getFqcn() + "#" + current.method().getNameAsString()
                    + ":" + current.method().getParameters().size();
            if (!visited.add(visitKey)) {
                continue;
            }
            out.add(current);

            if (!(current.owner().getTypeDeclaration() instanceof ClassOrInterfaceDeclaration declaration)) {
                continue;
            }

            List<ClassOrInterfaceType> parentTypes = new ArrayList<>();
            parentTypes.addAll(declaration.getExtendedTypes());
            parentTypes.addAll(declaration.getImplementedTypes());

            for (ClassOrInterfaceType parentType : parentTypes) {
                TypeDescriptor resolvedParent = index.resolveTypeReference(current.owner(), parentType.getNameAsString());
                if (resolvedParent == null) {
                    continue;
                }
                MethodDeclaration resolvedMethod = findMethod(
                        resolvedParent,
                        current.method().getNameAsString(),
                        null,
                        current.method().getParameters().size()
                );
                if (resolvedMethod != null) {
                    queue.addLast(new MethodContext(resolvedParent, resolvedMethod, current.originOwner()));
                }
            }
        }

        return out;
    }

    public static List<MethodContext> collectMethodContextsByHint(
            TypeDescriptor primaryType,
            String methodHint,
            Integer lineHint,
            TypeIndex index
    ) {
        List<MethodContext> out = new ArrayList<>();
        Set<String> visitedTypes = new HashSet<>();
        Deque<TypeDescriptor> queue = new ArrayDeque<>();
        queue.add(primaryType);

        while (!queue.isEmpty()) {
            TypeDescriptor currentType = queue.removeFirst();
            if (!visitedTypes.add(currentType.getFqcn())) {
                continue;
            }

            Integer currentLineHint = currentType == primaryType ? lineHint : null;
            MethodDeclaration candidate = findMethod(currentType, methodHint, currentLineHint, -1);
            if (candidate != null) {
                out.addAll(collectMethodContexts(primaryType, candidate, index));
                break;
            }

            if (!(currentType.getTypeDeclaration() instanceof ClassOrInterfaceDeclaration declaration)) {
                continue;
            }

            List<ClassOrInterfaceType> parentTypes = new ArrayList<>();
            parentTypes.addAll(declaration.getExtendedTypes());
            parentTypes.addAll(declaration.getImplementedTypes());

            for (ClassOrInterfaceType parentType : parentTypes) {
                TypeDescriptor resolvedParent = index.resolveTypeReference(currentType, parentType.getNameAsString());
                if (resolvedParent != null) {
                    queue.addLast(resolvedParent);
                }
            }
        }

        return out;
    }
}

