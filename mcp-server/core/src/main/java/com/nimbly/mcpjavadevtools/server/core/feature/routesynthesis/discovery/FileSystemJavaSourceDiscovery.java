package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceFile;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceIndex;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceMethod;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace.RouteSynthesisWorkspaceSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded source discovery and lightweight Java method indexing.
 *
 * <p>This parser intentionally owns only source structure needed by the first
 * target discovery actions. Framework handler semantics remain a later
 * Route Synthesis slice.</p>
 */
public class FileSystemJavaSourceDiscovery implements JavaSourceDiscovery {

    private static final int MAX_FILES = 1_500;
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", "node_modules", ".idea", ".vscode", "out", "target", "build");
    private static final Set<String> CONTROL_FLOW = Set.of(
            "if", "for", "while", "switch", "catch", "try", "do", "else", "new", "return",
            "throw", "case", "default", "synchronized");
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*;", Pattern.MULTILINE);
    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "^\\s*(public\\s+class\\s+|abstract\\s+class\\s+|final\\s+class\\s+|class\\s+|interface\\s+|enum\\s+|record\\s+)"
                    + "([A-Za-z_][A-Za-z0-9_]*)\\b",
            Pattern.MULTILINE);

    private final RouteSynthesisWorkspaceSnapshot workspace;

    /**
     * Creates a source discovery implementation bound to one workspace.
     *
     * @param workspace bound workspace snapshot
     */
    public FileSystemJavaSourceDiscovery(RouteSynthesisWorkspaceSnapshot workspace) {
        this.workspace = workspace;
    }

    /**
     * Discovers and parses a deterministic bounded source index.
     */
    @Override
    public JavaSourceIndex discover(
            Path projectRoot,
            List<Path> additionalSourceRoots,
            String classHint) {
        List<Path> roots = containedDistinctRoots(projectRoot, additionalSourceRoots);
        List<Path> files = collectJavaFiles(roots, classHint);
        List<JavaSourceFile> entries = new ArrayList<>();
        for (Path file : files) {
            parseFile(file).ifPresent(entries::add);
        }
        return new JavaSourceIndex(files.size(), entries);
    }

    private List<Path> containedDistinctRoots(Path projectRoot, List<Path> additionalRoots) {
        List<Path> roots = new ArrayList<>();
        addContainedRoot(roots, projectRoot);
        if (additionalRoots != null) {
            for (Path root : additionalRoots) {
                addContainedRoot(roots, root);
            }
        }
        return roots;
    }

    private void addContainedRoot(List<Path> roots, Path root) {
        if (root == null) {
            return;
        }
        Path normalized = root.toAbsolutePath().normalize();
        if (normalized.startsWith(workspace.workspaceRoot())
                && Files.isDirectory(normalized)
                && !roots.contains(normalized)) {
            roots.add(normalized);
        }
    }

    private List<Path> collectJavaFiles(List<Path> roots, String classHint) {
        Set<Path> unique = new HashSet<>();
        for (Path root : roots) {
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(this::isAllowedJavaFile)
                        .forEach(unique::add);
            } catch (IOException ignored) {
                // A bounded source index omits unreadable roots deterministically.
            }
        }
        Comparator<Path> ordering = Comparator.comparing(this::normalizedPath);
        List<Path> files = unique.stream().sorted(ordering).toList();
        if (classHint == null || classHint.isBlank()) {
            return files.stream().limit(MAX_FILES).toList();
        }
        String needle = classHint.trim().toLowerCase();
        return files.stream()
                .sorted(Comparator.comparing((Path path) -> !path.getFileName().toString()
                                .toLowerCase().contains(needle))
                        .thenComparing(ordering))
                .limit(MAX_FILES)
                .toList();
    }

    private boolean isAllowedJavaFile(Path path) {
        if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".java")) {
            return false;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspace.workspaceRoot())) {
            return false;
        }
        for (Path segment : workspace.workspaceRoot().relativize(normalized)) {
            if (EXCLUDED_DIRECTORIES.contains(segment.toString())) {
                return false;
            }
        }
        return true;
    }

    private String normalizedPath(Path path) {
        return path.toString().replace('\\', '/').toLowerCase();
    }

    private java.util.Optional<JavaSourceFile> parseFile(Path file) {
        try {
            String text = Files.readString(file);
            String packageName = matchGroup(PACKAGE_PATTERN, text);
            String className = matchClassName(text);
            List<JavaSourceMethod> methods = parseMethods(text);
            return java.util.Optional.of(new JavaSourceFile(file, packageName, className, methods));
        } catch (IOException ignored) {
            return java.util.Optional.empty();
        }
    }

    private String matchGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String matchClassName(String text) {
        Matcher matcher = CLASS_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(2) : null;
    }

    private List<JavaSourceMethod> parseMethods(String text) {
        String sanitized = sanitize(text);
        Map<Integer, Integer> bracePairs = bracePairs(sanitized);
        List<JavaSourceMethod> methods = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : bracePairs.entrySet()) {
            addMethodIfPresent(text, sanitized, entry.getKey(), entry.getValue(), methods);
        }
        methods.sort(Comparator.comparingInt(JavaSourceMethod::declarationLine)
                .thenComparing(JavaSourceMethod::name)
                .thenComparingInt(JavaSourceMethod::endLine));
        return methods;
    }

    private void addMethodIfPresent(
            String text,
            String sanitized,
            int openBrace,
            int closeBrace,
            List<JavaSourceMethod> methods) {
        int cursor = skipWhitespaceBackwards(sanitized, openBrace - 1);
        if (cursor < 0 || sanitized.charAt(cursor) != ')') {
            return;
        }
        int openParen = matchingParenBackwards(sanitized, cursor);
        if (openParen < 0) {
            return;
        }
        Identifier method = identifierBackwards(sanitized, openParen - 1);
        if (method == null || CONTROL_FLOW.contains(method.value())) {
            return;
        }
        int declarationStart = declarationStart(sanitized, method.start() - 1);
        String signature = normalize(text.substring(declarationStart, openBrace + 1));
        int declarationLine = lineNumberAt(method.start(), text);
        int endLine = lineNumberAt(closeBrace, text);
        int firstExecutableLine = firstExecutableLine(
                text, sanitized, openBrace + 1, closeBrace - 1, declarationLine);
        methods.add(new JavaSourceMethod(
                method.value(), signature, declarationLine, endLine, firstExecutableLine));
    }

    private Map<Integer, Integer> bracePairs(String sanitized) {
        Deque<Integer> stack = new ArrayDeque<>();
        Map<Integer, Integer> pairs = new HashMap<>();
        for (int index = 0; index < sanitized.length(); index++) {
            char value = sanitized.charAt(index);
            if (value == '{') {
                stack.push(index);
            } else if (value == '}' && !stack.isEmpty()) {
                pairs.put(stack.pop(), index);
            }
        }
        return pairs;
    }

    private String sanitize(String text) {
        StringBuilder output = new StringBuilder(text);
        SanitizeContext context = new SanitizeContext();
        int index = 0;
        while (index < output.length()) {
            if (context.mode == SanitizeMode.LINE) {
                index = sanitizeLine(output, index, context);
                index++;
                continue;
            }
            if (context.mode == SanitizeMode.BLOCK) {
                index = sanitizeBlock(output, index, context);
                continue;
            }
            if (context.mode == SanitizeMode.STRING || context.mode == SanitizeMode.CHARACTER) {
                index = sanitizeLiteral(output, index, context);
                continue;
            }
            index = sanitizeNormal(output, index, context);
        }
        return output.toString();
    }

    private int sanitizeLine(StringBuilder output, int index, SanitizeContext context) {
        if (output.charAt(index) == '\n') {
            context.mode = SanitizeMode.NORMAL;
            return index;
        }
        output.setCharAt(index, ' ');
        return index;
    }

    private int sanitizeBlock(StringBuilder output, int index, SanitizeContext context) {
        char value = output.charAt(index);
        char next = index + 1 < output.length() ? output.charAt(index + 1) : 0;
        if (value == '*' && next == '/') {
            output.setCharAt(index, ' ');
            output.setCharAt(index + 1, ' ');
            context.mode = SanitizeMode.NORMAL;
            return index + 2;
        }
        if (value != '\n') {
            output.setCharAt(index, ' ');
        }
        return index + 1;
    }

    private int sanitizeLiteral(StringBuilder output, int index, SanitizeContext context) {
        char value = output.charAt(index);
        boolean closing = !context.escaped && isClosingLiteral(context.mode, value);
        if (closing) {
            context.mode = SanitizeMode.NORMAL;
        }
        context.escaped = !context.escaped && value == '\\';
        if (value != '\n') {
            output.setCharAt(index, ' ');
        }
        return index + 1;
    }

    private boolean isClosingLiteral(SanitizeMode mode, char value) {
        return mode == SanitizeMode.STRING && value == '"'
                || mode == SanitizeMode.CHARACTER && value == '\'';
    }

    private int sanitizeNormal(StringBuilder output, int index, SanitizeContext context) {
        char value = output.charAt(index);
        char next = index + 1 < output.length() ? output.charAt(index + 1) : 0;
        if ((value == '/' && next == '/') || (value == '/' && next == '*')) {
            output.setCharAt(index, ' ');
            output.setCharAt(index + 1, ' ');
            context.mode = next == '/' ? SanitizeMode.LINE : SanitizeMode.BLOCK;
            return index + 2;
        }
        if (value == '"' || value == '\'') {
            output.setCharAt(index, ' ');
            context.mode = value == '"' ? SanitizeMode.STRING : SanitizeMode.CHARACTER;
            context.escaped = false;
        }
        return index + 1;
    }

    private int skipWhitespaceBackwards(String text, int index) {
        int cursor = index;
        while (cursor >= 0 && Character.isWhitespace(text.charAt(cursor))) {
            cursor--;
        }
        return cursor;
    }

    private int matchingParenBackwards(String text, int closeParen) {
        int depth = 0;
        for (int index = closeParen; index >= 0; index--) {
            char value = text.charAt(index);
            if (value == ')') {
                depth++;
            } else if (value == '(') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private Identifier identifierBackwards(String text, int index) {
        int end = skipWhitespaceBackwards(text, index);
        if (end < 0 || !isIdentifier(text.charAt(end))) {
            return null;
        }
        int start = end;
        while (start >= 0 && isIdentifier(text.charAt(start))) {
            start--;
        }
        return new Identifier(text.substring(start + 1, end + 1), start + 1);
    }

    private int declarationStart(String text, int before) {
        for (int index = before; index >= 0; index--) {
            char value = text.charAt(index);
            if (value == ';' || value == '{' || value == '}') {
                return index + 1;
            }
        }
        return 0;
    }

    private int firstExecutableLine(
            String text,
            String sanitized,
            int bodyStart,
            int bodyEnd,
            int declarationLine) {
        if (bodyEnd < bodyStart) {
            return declarationLine;
        }
        int startLine = lineNumberAt(bodyStart, text);
        int endLine = lineNumberAt(bodyEnd, text);
        for (int line = startLine; line <= endLine; line++) {
            String content = lineContent(text, sanitized, line, bodyStart, bodyEnd).trim();
            if (!content.isEmpty() && !content.equals("{") && !content.equals("}")
                    && !content.startsWith("@")) {
                return line;
            }
        }
        return declarationLine;
    }

    private String lineContent(
            String text,
            String sanitized,
            int line,
            int bodyStart,
            int bodyEnd) {
        int start = lineStart(text, line);
        int end = text.indexOf('\n', start);
        int boundedEnd = end < 0 ? text.length() : end;
        int sliceStart = Math.max(start, bodyStart);
        int sliceEnd = Math.min(boundedEnd, bodyEnd + 1);
        return sliceStart >= sliceEnd ? "" : sanitized.substring(sliceStart, sliceEnd);
    }

    private int lineNumberAt(int index, String text) {
        int line = 1;
        for (int cursor = 0; cursor < index && cursor < text.length(); cursor++) {
            if (text.charAt(cursor) == '\n') {
                line++;
            }
        }
        return line;
    }

    private int lineStart(String text, int line) {
        int current = 1;
        for (int index = 0; index < text.length(); index++) {
            if (current == line) {
                return index;
            }
            if (text.charAt(index) == '\n') {
                current++;
            }
        }
        return text.length();
    }

    private boolean isIdentifier(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    private String normalize(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private record Identifier(String value, int start) {
    }

    private enum SanitizeMode {
        NORMAL,
        LINE,
        BLOCK,
        STRING,
        CHARACTER
    }

    private static class SanitizeContext {
        private SanitizeMode mode = SanitizeMode.NORMAL;
        private boolean escaped;
    }
}
