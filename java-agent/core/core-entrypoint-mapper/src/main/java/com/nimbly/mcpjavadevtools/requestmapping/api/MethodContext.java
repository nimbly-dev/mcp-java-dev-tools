package com.nimbly.mcpjavadevtools.requestmapping.api;

import com.github.javaparser.ast.body.MethodDeclaration;

public record MethodContext(TypeDescriptor owner, MethodDeclaration method, TypeDescriptor originOwner) {
}




