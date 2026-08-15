package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.infertarget;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.infertarget.InferTargetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceFile;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceIndex;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceMethod;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisPathPolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Resolves source candidates from the bounded Java source index. */
public class InferTargetCandidateResolver {

    /** Finds candidates matching the requested class, method, and source line hints. */
    public List<RouteTargetCandidate> resolve(
            JavaSourceIndex index,
            Path projectRoot,
            InferTargetRequest request) {
        List<RouteTargetCandidate> candidates = new ArrayList<>();
        for (JavaSourceFile sourceFile : index.files()) {
            if (!classMatches(sourceFile, request.classHint())) {
                continue;
            }
            for (JavaSourceMethod method : sourceFile.methods()) {
                if (methodMatches(method, request.methodHint())
                        && lineMatches(method, request.lineHint())) {
                    candidates.add(toCandidate(projectRoot, sourceFile, method, request));
                }
            }
        }
        return candidates;
    }

    private boolean classMatches(JavaSourceFile sourceFile, String classHint) {
        String needle = classHint.trim().toLowerCase(Locale.ROOT);
        String fqcn = lower(sourceFile.fqcn());
        String className = lower(sourceFile.className());
        return needle.contains(".") ? needle.equals(fqcn) : needle.equals(className);
    }

    private boolean methodMatches(JavaSourceMethod method, String methodHint) {
        return methodHint == null || methodHint.isBlank()
                || method.name().equalsIgnoreCase(methodHint.trim());
    }

    private boolean lineMatches(JavaSourceMethod method, Integer lineHint) {
        return lineHint == null
                || lineHint == method.declarationLine()
                || lineHint == method.firstExecutableLine();
    }

    private RouteTargetCandidate toCandidate(
            Path projectRoot,
            JavaSourceFile sourceFile,
            JavaSourceMethod method,
            InferTargetRequest request) {
        String fqcn = sourceFile.fqcn();
        String key = fqcn == null ? null : fqcn + "#" + method.name();
        List<String> reasons = new ArrayList<>();
        reasons.add(request.classHint().equalsIgnoreCase(fqcn)
                ? "class fqcn exact match" : "class exact match");
        if (request.methodHint() != null && !request.methodHint().isBlank()) {
            reasons.add("method exact match");
        }
        if (request.lineHint() != null) {
            reasons.add("line exact match");
        }
        String normalizedSignature = method.signature().replaceAll("\\s+", " ");
        boolean returnsBoolean = normalizedSignature.matches(
                ".*\\b(boolean|Boolean|java\\.lang\\.Boolean)\\s+"
                        + java.util.regex.Pattern.quote(method.name()) + "\\s*\\(.*");
        return new RouteTargetCandidate(
                RouteSynthesisPathPolicy.relativePath(projectRoot, sourceFile.file()),
                sourceFile.className(),
                fqcn,
                method.name(),
                method.signature(),
                returnsBoolean,
                method.firstExecutableLine(),
                method.declarationLine(),
                method.endLine(),
                method.firstExecutableLine(),
                "unresolved",
                null,
                key,
                reasons);
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
