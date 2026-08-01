package com.nimbly.mcpjavadevtools.requestmapping.core;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.nimbly.mcpjavadevtools.requestmapping.api.TypeDescriptor;
import com.nimbly.mcpjavadevtools.requestmapping.api.TypeIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class TypeIndexBuilder {
    private TypeIndexBuilder() {
    }

    public static TypeIndex build(Path projectRoot) {
        return build(List.of(projectRoot));
    }

    public static TypeIndex build(List<Path> roots) {
        Map<String, List<TypeDescriptor>> bySimpleName = new HashMap<>();
        Map<String, List<TypeDescriptor>> byFqcn = new HashMap<>();
        int typeCount = 0;
        Set<Path> moduleRoots = new LinkedHashSet<>();

        for (Path root : roots) {
            if (root == null) {
                continue;
            }
            moduleRoots.addAll(SourceRootsScanner.discoverModuleRoots(root));
        }

        for (Path moduleRoot : moduleRoots) {
            for (Path sourceRoot : SourceRootsScanner.sourceRootsForModule(moduleRoot)) {
                if (!Files.isDirectory(sourceRoot)) {
                    continue;
                }
                try (Stream<Path> stream = Files.walk(sourceRoot)) {
                    List<Path> javaFiles = stream
                            .filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".java"))
                            .toList();
                    for (Path javaFile : javaFiles) {
                        List<TypeDescriptor> types = parseTypeDescriptors(javaFile);
                        for (TypeDescriptor descriptor : types) {
                            bySimpleName.computeIfAbsent(descriptor.getSimpleName(), ignored -> new ArrayList<>())
                                    .add(descriptor);
                            byFqcn.computeIfAbsent(descriptor.getFqcn(), ignored -> new ArrayList<>())
                                    .add(descriptor);
                            typeCount += 1;
                        }
                    }
                } catch (IOException ignored) {
                    // Skip unreadable roots; fail-closed happens during resolution.
                }
            }
        }

        return new TypeIndex(bySimpleName, byFqcn, typeCount);
    }

    public static List<TypeDescriptor> parseTypeDescriptors(Path javaFile) {
        try {
            CompilationUnit compilationUnit = StaticJavaParser.parse(javaFile);
            String packageName = compilationUnit.getPackageDeclaration()
                    .map(declaration -> declaration.getNameAsString())
                    .orElse("");
            List<String> imports = compilationUnit.getImports().stream()
                    .filter(importDeclaration -> !importDeclaration.isStatic())
                    .map(importDeclaration -> importDeclaration.getNameAsString()
                            + (importDeclaration.isAsterisk() ? ".*" : ""))
                    .toList();
            List<TypeDescriptor> out = new ArrayList<>();
            for (TypeDeclaration<?> type : compilationUnit.getTypes()) {
                String simpleName = type.getNameAsString();
                String fqcn = packageName.isBlank() ? simpleName : packageName + "." + simpleName;
                out.add(new TypeDescriptor(
                        javaFile.toAbsolutePath().normalize(),
                        type,
                        packageName,
                        simpleName,
                        fqcn,
                        imports
                ));
            }
            return out;
        } catch (IOException | ParseProblemException ignored) {
            return List.of();
        }
    }
}

