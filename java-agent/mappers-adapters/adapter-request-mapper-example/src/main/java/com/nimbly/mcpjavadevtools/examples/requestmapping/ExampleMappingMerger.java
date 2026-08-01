package com.nimbly.mcpjavadevtools.examples.requestmapping;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.nimbly.mcpjavadevtools.requestmapping.api.MethodContext;
import com.nimbly.mcpjavadevtools.requestmapping.api.TypeDescriptor;
import com.nimbly.mcpjavadevtools.requestmapping.api.TypeIndex;

import java.util.List;
import java.util.Optional;

public final class ExampleMappingMerger {
    private ExampleMappingMerger() {
    }

    public static String resolveClassPath(MethodContext context, TypeIndex index) {
        String originPath = resolveTypePath(context.originOwner(), index);
        if (!originPath.isBlank()) {
            return originPath;
        }
        return resolveTypePath(context.owner(), index);
    }

    public static Optional<ExampleMethodMapping> resolveMethodMapping(MethodContext context, TypeIndex index) {
        for (AnnotationExpr annotation : context.method().getAnnotations()) {
            String simpleName = ExampleAnnotationNames.simpleName(annotation.getNameAsString());
            String composedMethod = ExampleAnnotationNames.COMPOSED_MAPPINGS.get(simpleName);
            if (composedMethod != null) {
                String methodPath = resolvePathValue(annotation, context.owner(), index);
                return Optional.of(new ExampleMethodMapping(composedMethod, methodPath));
            }
            if (!simpleName.equals(ExampleAnnotationNames.EXAMPLE_ROUTE)) {
                continue;
            }

            Optional<String> httpMethod = resolveRouteHttpMethod(annotation);
            if (httpMethod.isEmpty()) {
                continue;
            }
            String methodPath = resolvePathValue(annotation, context.owner(), index);
            return Optional.of(new ExampleMethodMapping(httpMethod.get(), methodPath));
        }
        return Optional.empty();
    }

    private static String resolveTypePath(TypeDescriptor owner, TypeIndex index) {
        for (AnnotationExpr annotation : owner.getTypeDeclaration().getAnnotations()) {
            String simpleName = ExampleAnnotationNames.simpleName(annotation.getNameAsString());
            if (simpleName.equals(ExampleAnnotationNames.EXAMPLE_CONTROLLER)
                    || simpleName.equals(ExampleAnnotationNames.EXAMPLE_ROUTE)) {
                return resolvePathValue(annotation, owner, index);
            }
        }
        return "";
    }

    private static Optional<String> resolveRouteHttpMethod(AnnotationExpr annotation) {
        if (!(annotation instanceof NormalAnnotationExpr normalAnnotation)) {
            return Optional.empty();
        }
        for (var pair : normalAnnotation.getPairs()) {
            if (!pair.getNameAsString().equals("method")) {
                continue;
            }
            String resolved = resolveMethodExpression(pair.getValue()).trim();
            if (resolved.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(resolved.toUpperCase());
        }
        return Optional.empty();
    }

    private static String resolveMethodExpression(Expression expression) {
        if (expression instanceof StringLiteralExpr stringLiteral) {
            return stringLiteral.getValue();
        }
        if (expression instanceof FieldAccessExpr fieldAccessExpr) {
            return fieldAccessExpr.getNameAsString();
        }
        if (expression instanceof NameExpr nameExpr) {
            return nameExpr.getNameAsString();
        }
        if (expression instanceof ArrayInitializerExpr arrayInitializerExpr) {
            NodeList<Expression> values = arrayInitializerExpr.getValues();
            if (!values.isEmpty()) {
                return resolveMethodExpression(values.get(0));
            }
        }
        String raw = expression.toString();
        int idx = raw.lastIndexOf('.');
        return idx >= 0 ? raw.substring(idx + 1) : raw;
    }

    public static String resolvePathValue(AnnotationExpr annotation, TypeDescriptor owner, TypeIndex index) {
        if (annotation instanceof SingleMemberAnnotationExpr singleMember) {
            return resolveStringExpression(singleMember.getMemberValue(), owner, index);
        }
        if (annotation instanceof NormalAnnotationExpr normalAnnotation) {
            for (String candidate : List.of("path", "value")) {
                Optional<Expression> value = normalAnnotation.getPairs().stream()
                        .filter(pair -> pair.getNameAsString().equals(candidate))
                        .map(pair -> pair.getValue())
                        .findFirst();
                if (value.isPresent()) {
                    return resolveStringExpression(value.get(), owner, index);
                }
            }
        }
        return "";
    }

    private static String resolveStringExpression(Expression expression, TypeDescriptor owner, TypeIndex index) {
        if (expression instanceof StringLiteralExpr stringLiteral) {
            return stringLiteral.getValue();
        }
        if (expression instanceof TextBlockLiteralExpr textBlockLiteral) {
            return textBlockLiteral.getValue();
        }
        if (expression instanceof NameExpr nameExpr) {
            return owner.getStringConstants().getOrDefault(nameExpr.getNameAsString(), "");
        }
        if (expression instanceof FieldAccessExpr fieldAccessExpr) {
            String scope = fieldAccessExpr.getScope().toString();
            String fieldName = fieldAccessExpr.getNameAsString();
            TypeDescriptor target = index.resolveTypeReference(owner, scope);
            if (target != null) {
                return target.getStringConstants().getOrDefault(fieldName, "");
            }
        }
        if (expression instanceof BinaryExpr binaryExpr && binaryExpr.getOperator() == BinaryExpr.Operator.PLUS) {
            return resolveStringExpression(binaryExpr.getLeft(), owner, index)
                    + resolveStringExpression(binaryExpr.getRight(), owner, index);
        }
        if (expression instanceof ArrayInitializerExpr arrayInitializerExpr) {
            NodeList<Expression> values = arrayInitializerExpr.getValues();
            if (!values.isEmpty()) {
                return resolveStringExpression(values.get(0), owner, index);
            }
        }
        return "";
    }

    public record ExampleMethodMapping(String httpMethod, String methodPath) {
    }
}
