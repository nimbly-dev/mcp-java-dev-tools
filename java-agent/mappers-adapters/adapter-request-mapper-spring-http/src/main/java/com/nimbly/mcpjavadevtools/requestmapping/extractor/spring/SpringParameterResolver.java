package com.nimbly.mcpjavadevtools.requestmapping.extractor.spring;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.nimbly.mcpjavadevtools.requestmapping.api.MethodContext;
import com.nimbly.mcpjavadevtools.requestmapping.api.ResolvedParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SpringParameterResolver {
    private SpringParameterResolver() {
    }

    public static List<ResolvedParameter> resolve(MethodContext context) {
        List<ResolvedParameter> out = new ArrayList<>();
        for (Parameter parameter : context.method().getParameters()) {
            Optional<ResolvedParameter> resolved = resolveParameter(parameter);
            resolved.ifPresent(out::add);
        }
        return out;
    }

    private static Optional<ResolvedParameter> resolveParameter(Parameter parameter) {
        for (AnnotationExpr annotation : parameter.getAnnotations()) {
            String name = SpringAnnotationNames.simpleName(annotation.getNameAsString());
            if (name.equals("RequestParam") || name.equals("QueryParam")) {
                String requestName = resolveNamedParameter(annotation, parameter.getNameAsString());
                return Optional.of(new ResolvedParameter("query", requestName, parameter.getType()));
            }
            if (name.equals("PathVariable") || name.equals("PathParam")) {
                String requestName = resolveNamedParameter(annotation, parameter.getNameAsString());
                return Optional.of(new ResolvedParameter("path", requestName, parameter.getType()));
            }
            if (name.equals("RequestBody")) {
                return Optional.of(new ResolvedParameter("body", parameter.getNameAsString(), parameter.getType()));
            }
            if (name.equals("RequestHeader") || name.equals("HeaderParam")) {
                String requestName = resolveNamedParameter(annotation, parameter.getNameAsString());
                return Optional.of(new ResolvedParameter("header", requestName, parameter.getType()));
            }
        }
        return Optional.empty();
    }

    private static String resolveNamedParameter(AnnotationExpr annotation, String fallback) {
        if (annotation instanceof SingleMemberAnnotationExpr singleMember
                && singleMember.getMemberValue() instanceof StringLiteralExpr stringLiteral) {
            return stringLiteral.getValue();
        }
        if (annotation instanceof NormalAnnotationExpr normalAnnotation) {
            for (String candidate : List.of("name", "value")) {
                Optional<Expression> value = normalAnnotation.getPairs().stream()
                        .filter(pair -> pair.getNameAsString().equals(candidate))
                        .map(pair -> pair.getValue())
                        .findFirst();
                if (value.isPresent() && value.get() instanceof StringLiteralExpr stringLiteral) {
                    return stringLiteral.getValue();
                }
            }
        }
        return fallback;
    }
}
