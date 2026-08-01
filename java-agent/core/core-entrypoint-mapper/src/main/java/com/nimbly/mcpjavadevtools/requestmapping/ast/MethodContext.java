package com.nimbly.mcpjavadevtools.requestmapping.ast;

import com.github.javaparser.ast.body.MethodDeclaration;

/** Binary-compatible Request Mapper context retained for the pre-api SPI. */
@Deprecated(forRemoval = false)
public record MethodContext(TypeDescriptor owner, MethodDeclaration method, TypeDescriptor originOwner) {
}
