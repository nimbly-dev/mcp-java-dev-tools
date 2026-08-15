package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery;

/**
 * Parsed Java method source span.
 *
 * @param name method name
 * @param signature normalized source signature
 * @param declarationLine method declaration line
 * @param endLine closing-brace line
 * @param firstExecutableLine first non-annotation body line
 */
public record JavaSourceMethod(
        String name,
        String signature,
        int declarationLine,
        int endLine,
        int firstExecutableLine) {
}
