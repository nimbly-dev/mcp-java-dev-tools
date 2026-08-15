package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.RouteSynthesisHandlerDiscoveryResult;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.RouteSynthesisHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceFile;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceIndex;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceMethod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-backed Spring HTTP mapping discovery used by Core route actions.
 *
 * <p>This parser intentionally covers the deterministic mapping forms needed
 * by the compatibility contract. It does not load application classes or
 * launch another runtime.</p>
 */
public class SpringHttpHandlerDiscovery implements RouteSynthesisHandlerDiscovery {

    private static final int ANNOTATION_LOOKBACK_LINES = 16;
    private static final Pattern MAPPING_PATTERN = Pattern.compile(
            "@(GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping|RequestMapping)"
                    + "\\s*(?:\\(([^)]*)\\))?",
            Pattern.MULTILINE);
    private static final Pattern QUOTED_VALUE = Pattern.compile("[\\\"]([^\\\"]+)[\\\"]");
    private static final Pattern REQUEST_METHOD = Pattern.compile(
            "RequestMethod\\.([A-Z]+)");

    private final JavaSourceDiscovery sourceDiscovery;

    /**
     * Creates Spring HTTP discovery over a contained source index.
     *
     * @param sourceDiscovery contained Java source discovery
     */
    public SpringHttpHandlerDiscovery(JavaSourceDiscovery sourceDiscovery) {
        this.sourceDiscovery = sourceDiscovery;
    }

    /**
     * Resolves one exact controller and its declaration-ordered mappings.
     */
    @Override
    public RouteSynthesisHandlerDiscoveryResult discover(
            Path projectRoot,
            List<Path> additionalSourceRoots,
            String classHint) {
        JavaSourceIndex index = sourceDiscovery.discover(projectRoot, additionalSourceRoots, classHint);
        List<JavaSourceFile> matches = exactMatches(index, classHint);
        if (matches.isEmpty()) {
            return failure("target_type_not_found", "target_inference", "refine_target_hints",
                    index.scannedJavaFiles(), List.of("classHint=" + safeHint(classHint)));
        }
        if (matches.size() > 1) {
            return failure("target_type_ambiguous", "target_selection", "disambiguate_target",
                    index.scannedJavaFiles(), List.of("candidateCount=" + matches.size()));
        }
        return discoverController(index, matches.get(0));
    }

    private RouteSynthesisHandlerDiscoveryResult discoverController(
            JavaSourceIndex index,
            JavaSourceFile sourceFile) {
        String source = readSource(sourceFile.file());
        if (!isSpringController(source, sourceFile.className())) {
            return failure("mapper_plugin_unavailable", "request_mapping_resolver_bootstrap",
                    "use_typescript_compatibility_implementation", index.scannedJavaFiles(),
                    List.of("framework=spring_http"));
        }
        String classMapping = mappingAt(source, sourceLine(source, sourceFile.className()));
        String classPath = classMapping == null ? "/" : parseMapping(classMapping).path();
        List<RouteSynthesisHandler> handlers = sourceFile.methods().stream()
                .sorted(Comparator.comparingInt(JavaSourceMethod::declarationLine)
                        .thenComparing(JavaSourceMethod::name))
                .map(method -> toHandler(source, sourceFile, method, classPath))
                .flatMap(java.util.Optional::stream)
                .toList();
        if (handlers.isEmpty()) {
            return failure("mapper_plugin_unavailable", "request_mapping_resolver_bootstrap",
                    "use_typescript_compatibility_implementation", index.scannedJavaFiles(),
                    List.of("framework=spring_http"));
        }
        return RouteSynthesisHandlerDiscoveryResult.success(
                sourceFile.fqcn(), sourceFile.file(), handlers, index.scannedJavaFiles(),
                List.of("framework=spring_http", "handlerCount=" + handlers.size()));
    }

    private java.util.Optional<RouteSynthesisHandler> toHandler(
            String source,
            JavaSourceFile sourceFile,
            JavaSourceMethod method,
            String classPath) {
        String annotation = mappingAt(source, method.declarationLine());
        if (annotation == null) {
            return java.util.Optional.empty();
        }
        Mapping mapping = parseMapping(annotation);
        String path = joinPaths(classPath, mapping.path());
        String key = sourceFile.fqcn() + "#" + method.name();
        Integer firstLine = method.firstExecutableLine();
        String strictKey = key + ":" + firstLine;
        return java.util.Optional.of(new RouteSynthesisHandler(
                mapping.httpMethod(), path, method.name(), method.signature(), sourceFile.fqcn(),
                method.declarationLine(), method.endLine(), firstLine, "unresolved", null, null, strictKey));
    }

    private List<JavaSourceFile> exactMatches(JavaSourceIndex index, String classHint) {
        String needle = classHint == null ? "" : classHint.trim().toLowerCase(Locale.ROOT);
        return index.files().stream()
                .filter(file -> needle.equals(lower(file.fqcn())))
                .sorted(Comparator.comparing(file -> file.file().toString()))
                .toList();
    }

    private String mappingAt(String source, int line) {
        int startLine = Math.max(1, line - ANNOTATION_LOOKBACK_LINES);
        String region = lines(source, startLine, line);
        Matcher matcher = MAPPING_PATTERN.matcher(region);
        String value = null;
        while (matcher.find()) {
            value = matcher.group();
        }
        return value;
    }

    private Mapping parseMapping(String annotation) {
        Matcher matcher = MAPPING_PATTERN.matcher(annotation);
        if (!matcher.find()) {
            return new Mapping("GET", "/");
        }
        String annotationName = matcher.group(1);
        String arguments = matcher.group(2) == null ? "" : matcher.group(2);
        String method = methodFor(annotationName, arguments);
        Matcher pathMatcher = QUOTED_VALUE.matcher(arguments);
        String path = pathMatcher.find() ? pathMatcher.group(1) : "/";
        return new Mapping(method, path);
    }

    private String methodFor(String annotationName, String arguments) {
        return switch (annotationName) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "PatchMapping" -> "PATCH";
            case "DeleteMapping" -> "DELETE";
            default -> requestMethod(arguments);
        };
    }

    private String requestMethod(String arguments) {
        Matcher matcher = REQUEST_METHOD.matcher(arguments);
        return matcher.find() ? matcher.group(1) : "GET";
    }

    private String joinPaths(String parent, String child) {
        String left = parent == null || parent.isBlank() ? "" : parent.trim();
        String right = child == null || child.isBlank() ? "" : child.trim();
        String joined = (left + "/" + right).replaceAll("/{2,}", "/");
        return joined.startsWith("/") ? joined : "/" + joined;
    }

    private boolean isSpringController(String source, String className) {
        int classLine = sourceLine(source, className);
        String region = lines(source, Math.max(1, classLine - ANNOTATION_LOOKBACK_LINES), classLine);
        return region.contains("@RestController") || region.contains("@Controller");
    }

    private int sourceLine(String source, String token) {
        int offset = token == null ? -1 : source.indexOf(token);
        if (offset < 0) {
            return 1;
        }
        return (int) source.substring(0, offset).chars().filter(value -> value == '\n').count() + 1;
    }

    private String lines(String source, int start, int end) {
        String[] values = source.split("\\R", -1);
        int from = Math.max(0, start - 1);
        int to = Math.min(values.length, end);
        StringBuilder result = new StringBuilder();
        for (int index = from; index < to; index++) {
            result.append(values[index]).append('\n');
        }
        return result.toString();
    }

    private String readSource(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException exception) {
            return "";
        }
    }

    private RouteSynthesisHandlerDiscoveryResult failure(
            String reasonCode,
            String failedStep,
            String nextAction,
            int scannedFiles,
            List<String> evidence) {
        String status = "mapper_plugin_unavailable".equals(reasonCode) ? "blocked" : "report";
        return new RouteSynthesisHandlerDiscoveryResult(
                status, reasonCode, failedStep, nextAction, null, null, null, List.of(),
                scannedFiles, evidence, List.of("java_source_index_lookup", "spring_http_annotation_resolution"));
    }

    private String safeHint(String value) {
        return value == null || value.isBlank() ? "missing" : value.trim();
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record Mapping(String httpMethod, String path) {
    }
}
