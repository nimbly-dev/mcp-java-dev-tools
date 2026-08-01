package com.nimbly.mcpjavadevtools.requestmapping.ast;

import com.github.javaparser.ast.type.Type;

/** Binary-compatible Request Mapper parameter value retained for the pre-api SPI. */
@Deprecated(forRemoval = false)
public final class ResolvedParameter {
    private final String kind;
    private final String name;
    private final Type type;

    public ResolvedParameter(String kind, String name, Type type) {
        this.kind = kind;
        this.name = name;
        this.type = type;
    }

    public String getKind() { return kind; }
    public String getName() { return name; }
    public Type getType() { return type; }
}
