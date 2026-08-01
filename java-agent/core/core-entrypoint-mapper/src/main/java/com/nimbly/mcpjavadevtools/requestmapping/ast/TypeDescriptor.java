package com.nimbly.mcpjavadevtools.requestmapping.ast;

import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Binary-compatible type descriptor retained for the pre-api SPI. */
@Deprecated(forRemoval = false)
public final class TypeDescriptor {
    private final Path fileAbs;
    private final TypeDeclaration<?> typeDeclaration;
    private final String packageName;
    private final String simpleName;
    private final String fqcn;
    private final List<String> imports;
    private final Map<String, String> stringConstants;

    public TypeDescriptor(Path fileAbs, TypeDeclaration<?> typeDeclaration, String packageName,
                          String simpleName, String fqcn, List<String> imports) {
        this.fileAbs = fileAbs;
        this.typeDeclaration = typeDeclaration;
        this.packageName = packageName;
        this.simpleName = simpleName;
        this.fqcn = fqcn;
        this.imports = imports;
        this.stringConstants = collectStringConstants(typeDeclaration);
    }

    public Path getFileAbs() { return fileAbs; }
    public TypeDeclaration<?> getTypeDeclaration() { return typeDeclaration; }
    public String getPackageName() { return packageName; }
    public String getSimpleName() { return simpleName; }
    public String getFqcn() { return fqcn; }
    public List<String> getImports() { return imports; }
    public Map<String, String> getStringConstants() { return stringConstants; }

    private static Map<String, String> collectStringConstants(TypeDeclaration<?> declaration) {
        Map<String, String> out = new LinkedHashMap<>();
        for (BodyDeclaration<?> member : declaration.getMembers()) {
            if (!(member instanceof FieldDeclaration fieldDeclaration) || !fieldDeclaration.isStatic()) continue;
            fieldDeclaration.getVariables().forEach(variable -> variable.getInitializer()
                    .filter(StringLiteralExpr.class::isInstance)
                    .map(StringLiteralExpr.class::cast)
                    .ifPresent(literal -> out.put(variable.getNameAsString(), literal.getValue())));
        }
        return out;
    }
}
