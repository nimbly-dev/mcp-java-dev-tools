package com.nimbly.mcpjavadevtools.requestmapping.extractor.spring;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
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

public final class SpringMappingMerger {
    private SpringMappingMerger() {
    }

    public static String resolveClassPath(MethodContext context, TypeIndex index) {
        String originPath = resolveTypeRequestMappingPath(context.originOwner(), index);
        if (!originPath.isBlank()) {
            return originPath;
        }
        return resolveTypeRequestMappingPath(context.owner(), index);
    }

    public static Optional<SpringMethodMapping> resolveMethodMapping(MethodContext context, TypeIndex index) {
        MethodDeclaration method = context.method();
        Optional<SpringMethodMapping> resolvedMapping = Optional.empty();
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String simpleName = SpringAnnotationNames.simpleName(annotation.getNameAsString());
            String composedMethod = SpringAnnotationNames.COMPOSED_MAPPINGS.get(simpleName);
            if (composedMethod != null) {
                String methodPath = resolvePathValue(annotation, context.owner(), index);
                resolvedMapping = Optional.of(new SpringMethodMapping(composedMethod, methodPath));
            } else if (simpleName.equals(SpringAnnotationNames.REQUEST_MAPPING)) {
                Optional<String> httpMethod = resolveRequestMappingHttpMethod(annotation);
                if (httpMethod.isPresent()) {
                    String methodPath = resolvePathValue(annotation, context.owner(), index);
                    resolvedMapping = Optional.of(new SpringMethodMapping(httpMethod.get(), methodPath));
                }
            }
            if (resolvedMapping.isPresent()) {
                break;
            }
        }
        return resolvedMapping;
    }

    private static String resolveTypeRequestMappingPath(TypeDescriptor owner, TypeIndex index) {
        for (AnnotationExpr annotation : owner.getTypeDeclaration().getAnnotations()) {
            String simpleName = SpringAnnotationNames.simpleName(annotation.getNameAsString());
            if (simpleName.equals(SpringAnnotationNames.REQUEST_MAPPING)) {
                return resolvePathValue(annotation, owner, index);
            }
        }
        return "";
    }

    private static Optional<String> resolveRequestMappingHttpMethod(AnnotationExpr annotation) {
        if (!(annotation instanceof NormalAnnotationExpr normalAnnotation)) {
            return Optional.empty();
        }
        Optional<String> resolvedMethod = Optional.empty();
        for (var pair : normalAnnotation.getPairs()) {
            if (pair.getNameAsString().equals("method")) {
                resolvedMethod = resolveRequestMethodExpression(pair.getValue());
            }
        }
        return resolvedMethod;
    }

    private static Optional<String> resolveRequestMethodExpression(Expression expression) {
        if (expression instanceof FieldAccessExpr fieldAccessExpr) {
            return Optional.of(parseRequestMethodToken(fieldAccessExpr.getNameAsString()));
        }
        if (expression instanceof NameExpr nameExpr) {
            return Optional.of(parseRequestMethodToken(nameExpr.getNameAsString()));
        }
        if (expression instanceof ArrayInitializerExpr arrayInitializerExpr) {
            NodeList<Expression> values = arrayInitializerExpr.getValues();
            if (values.isEmpty()) {
                return Optional.empty();
            }
            return resolveRequestMethodExpression(values.get(0));
        }
        String raw = expression.toString();
        int idx = raw.lastIndexOf('.');
        return Optional.of(parseRequestMethodToken(idx >= 0 ? raw.substring(idx + 1) : raw));
    }

    private static String parseRequestMethodToken(String token) {
        String trimmed = token.trim();
        if (trimmed.equalsIgnoreCase("get")) return "GET";
        if (trimmed.equalsIgnoreCase("post")) return "POST";
        if (trimmed.equalsIgnoreCase("put")) return "PUT";
        if (trimmed.equalsIgnoreCase("patch")) return "PATCH";
        if (trimmed.equalsIgnoreCase("delete")) return "DELETE";
        return trimmed.toUpperCase();
    }

    public static String resolvePathValue(
            AnnotationExpr annotation,
            TypeDescriptor owner,
            TypeIndex index
    ) {
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

    private static String resolveStringExpression(
            Expression expression,
            TypeDescriptor owner,
            TypeIndex index
    ) {
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

    public record SpringMethodMapping(String httpMethod, String methodPath) {
    }
}

