package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.classmethods;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.classmethods.ClassMethodsMatchesResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.classmethods.ClassMethodsRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.classmethods.ClassMethodsResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceFile;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceIndex;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisDisambiguationDetails;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisReportDetails;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetClass;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetClassMatch;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetHints;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisPathPolicy;
import java.nio.file.Path;
import java.util.List;

/** Assembles class_methods reports, ambiguity payloads, and success output. */
public class ClassMethodsResultAssembler {

    /** Builds the deterministic missing-class result. */
    public RouteSynthesisResult notFound(
            Path projectRoot,
            ClassMethodsRequest request,
            List<Path> additionalRoots,
            JavaSourceIndex index) {
        ClassMethodsResult output = new ClassMethodsResult(
                projectRoot.toString(),
                hints(projectRoot, request),
                additionalRoots.stream().map(Path::toString).toList(),
                index.scannedJavaFiles(),
                null);
        return RouteSynthesisResult.report(
                "class_methods",
                new RouteSynthesisReportDetails(
                        "class_not_found", "class_not_found", "target_inference", "class_not_found",
                        "Refine classHint (prefer an exact fully qualified class name) and rerun class_methods.",
                        List.of(), List.of()),
                output);
    }

    /** Builds the deterministic ambiguous-class result. */
    public RouteSynthesisResult ambiguous(
            Path projectRoot,
            ClassMethodsRequest request,
            List<Path> additionalRoots,
            JavaSourceIndex index,
            List<JavaSourceFile> matches) {
        List<RouteTargetClassMatch> candidates = matches.stream()
                .map(file -> new RouteTargetClassMatch(
                        RouteSynthesisPathPolicy.relativePath(projectRoot, file.file()),
                        file.className(),
                        file.fqcn()))
                .toList();
        ClassMethodsMatchesResult output = new ClassMethodsMatchesResult(
                projectRoot.toString(),
                hints(projectRoot, request),
                additionalRoots.stream().map(Path::toString).toList(),
                index.scannedJavaFiles(),
                candidates);
        return RouteSynthesisResult.disambiguation(new RouteSynthesisDisambiguationDetails(
                "disambiguation", "class_ambiguous", "class_ambiguous", "target_selection",
                "class_ambiguous", "Refine classHint to an exact fully qualified class name.", output));
    }

    /** Builds the selected-class success result. */
    public RouteSynthesisResult success(
            Path projectRoot,
            ClassMethodsRequest request,
            List<Path> additionalRoots,
            JavaSourceIndex index,
            RouteTargetClass target) {
        ClassMethodsResult output = new ClassMethodsResult(
                projectRoot.toString(),
                hints(projectRoot, request),
                additionalRoots.stream().map(Path::toString).toList(),
                index.scannedJavaFiles(),
                target);
        return RouteSynthesisResult.success("class_methods", output);
    }

    private RouteTargetHints hints(Path projectRoot, ClassMethodsRequest request) {
        return new RouteTargetHints(
                projectRoot.toString(), request.classHint(), null, null, "class_methods");
    }
}
