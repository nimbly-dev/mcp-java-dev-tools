package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Writes the portable performance bundle and JMeter artifacts for an export. */
public final class ExecutionExportPerformanceArtifacts {

    private final ObjectMapper mapper;

    public ExecutionExportPerformanceArtifacts(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<String> write(
            Path export,
            String exportId,
            String executionPolicy,
            ExecutionExportWorkload.Workload workload) {
        List<String> jmeterPaths = new ArrayList<>();
        ObjectNode bundle = mapper.createObjectNode();
        bundle.put("exportId", exportId);
        bundle.put("suiteType", "performance");
        bundle.put("executionProfile", workload.executionProfile() == null
                ? "ad-hoc" : workload.executionProfile());
        bundle.put("executionPolicy", executionPolicy);
        var plans = bundle.putArray("plans");
        for (ExecutionExportWorkload.PlanWorkload plan : workload.plans()) {
            ObjectNode planNode = plans.addObject();
            planNode.put("order", plan.order());
            planNode.put("planName", plan.planName());
            JsonNode observation = plan.contract().path("observationTargets");
            if (observation.path("baseUrl").isTextual()) {
                planNode.put("probeBaseUrl", observation.path("baseUrl").asText());
            }
            planNode.set("contract", plan.contract());
            if (isJmeter(plan.contract())) {
                String relative = writeJmeter(export, plan);
                planNode.putObject("exportedArtifacts").put("jmxPathRel", relative);
                jmeterPaths.add(relative);
            }
        }
        writeText(export.resolve("performance-export.bundle.json"), pretty(bundle));
        writeText(export.resolve("run-performance-profile.js"), runnerScript());
        return List.copyOf(jmeterPaths);
    }

    private String writeJmeter(Path export, ExecutionExportWorkload.PlanWorkload plan) {
        JsonNode entrypoint = plan.contract().path("entrypoints").path(0);
        if (entrypoint.isMissingNode()) {
            throw new ArtifactOperationException("performance_export_plan_invalid",
                    "JMeter export requires an HTTP entrypoint");
        }
        JsonNode request = entrypoint.path("request");
        String method = request.path("method").asText("GET").toUpperCase(Locale.ROOT);
        String url = requestUrl(entrypoint);
        Map<String, String> headers = headers(entrypoint);
        String body = requestBody(request);
        String relative = "artifacts/jmeter/" + safeSegment(plan.planName()) + ".workload.jmeter.jmx";
        Path target = export.resolve(relative.replace('/', File.separatorChar));
        writeText(target, renderJmx(plan.planName(), method, url, headers, body, plan.contract().path("loadModel")));
        return relative;
    }

    private String requestUrl(JsonNode entrypoint) {
        JsonNode transport = entrypoint.path("transport");
        JsonNode request = entrypoint.path("request");
        String base = transport.path("baseUrl").asText("").replaceAll("/$", "");
        String path = request.path("path").asText(request.path("pathTemplate").asText(""));
        String url = base + (path.startsWith("/") || path.isBlank() ? path : "/" + path);
        JsonNode query = request.path("queryTemplate");
        if (!query.isObject() || query.size() == 0) {
            return url;
        }
        List<String> values = new ArrayList<>();
        query.fields().forEachRemaining(entry -> values.add(encode(entry.getKey()) + "=" + encode(entry.getValue().isValueNode()
                ? entry.getValue().asText() : entry.getValue().toString())));
        values.sort(String::compareTo);
        return url + "?" + String.join("&", values);
    }

    private Map<String, String> headers(JsonNode entrypoint) {
        Map<String, String> headers = new TreeMap<>();
        entrypoint.path("transport").path("defaultHeaders").fields()
                .forEachRemaining(entry -> headers.put(entry.getKey(), entry.getValue().asText()));
        entrypoint.path("request").path("headers").fields()
                .forEachRemaining(entry -> headers.put(entry.getKey(), entry.getValue().asText()));
        return headers;
    }

    private String renderJmx(
            String planName,
            String method,
            String url,
            Map<String, String> headers,
            String body,
            JsonNode loadModel) {
        String headerBlock = renderHeaders(headers);
        String bodyBlock = renderBody(body);
        int concurrency = loadModel.path("concurrency").asInt(1);
        int ramp = loadModel.path("rampUpSeconds").asInt(0);
        int duration = loadModel.path("durationSeconds").asInt(1);
        StringBuilder output = new StringBuilder();
        output.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        output.append("<jmeterTestPlan version=\"1.2\" properties=\"5.0\" jmeter=\"5.6.3\">\n");
        output.append("  <hashTree>\n");
        output.append("    <TestPlan guiclass=\"TestPlanGui\" testclass=\"TestPlan\" testname=\"")
                .append(xml(planName)).append("\" enabled=\"true\">\n");
        output.append("      <stringProp name=\"TestPlan.comments\"></stringProp>\n");
        output.append("      <boolProp name=\"TestPlan.functional_mode\">false</boolProp>\n");
        output.append("      <boolProp name=\"TestPlan.tearDown_on_shutdown\">true</boolProp>\n");
        output.append("      <boolProp name=\"TestPlan.serialize_threadgroups\">false</boolProp>\n");
        output.append("      <elementProp name=\"TestPlan.user_defined_variables\" elementType=\"Arguments\">")
                .append("\n        <collectionProp name=\"Arguments.arguments\"/>\n")
                .append("      </elementProp>\n");
        output.append("      <stringProp name=\"TestPlan.user_define_classpath\"></stringProp>\n");
        output.append("    </TestPlan>\n    <hashTree>\n");
        output.append(threadGroup(concurrency, ramp, duration));
        output.append(headerBlock);
        output.append(renderSampler(method, url, bodyBlock));
        output.append("      </hashTree>\n    </hashTree>\n  </hashTree>\n");
        return output.append("</jmeterTestPlan>\n").toString();
    }

    private String renderSampler(String method, String url, String bodyBlock) {
        JmeterTarget target = jmeterTarget(url);
        StringBuilder output = new StringBuilder();
        output.append("        <HTTPSamplerProxy guiclass=\"HttpTestSampleGui\" ")
                .append("testclass=\"HTTPSamplerProxy\" testname=\"")
                .append(xml(method + " " + url)).append("\" enabled=\"true\">\n");
        output.append("          <stringProp name=\"HTTPSampler.domain\">").append(xml(target.domain()))
                .append("</stringProp>\n");
        output.append("          <stringProp name=\"HTTPSampler.port\">").append(target.port())
                .append("</stringProp>\n");
        output.append("          <stringProp name=\"HTTPSampler.protocol\">").append(xml(target.protocol()))
                .append("</stringProp>\n");
        output.append("          <stringProp name=\"HTTPSampler.contentEncoding\"></stringProp>\n");
        output.append("          <stringProp name=\"HTTPSampler.path\">").append(xml(target.path()))
                .append("</stringProp>\n");
        output.append("          <stringProp name=\"HTTPSampler.method\">").append(xml(method))
                .append("</stringProp>\n");
        output.append(bodyBlock);
        output.append("          <boolProp name=\"HTTPSampler.follow_redirects\">true</boolProp>\n");
        output.append("          <boolProp name=\"HTTPSampler.auto_redirects\">false</boolProp>\n");
        output.append("          <boolProp name=\"HTTPSampler.use_keepalive\">true</boolProp>\n");
        output.append("          <boolProp name=\"HTTPSampler.DO_MULTIPART_POST\">false</boolProp>\n");
        output.append("          <stringProp name=\"HTTPSampler.embedded_url_re\"></stringProp>\n");
        output.append("          <stringProp name=\"HTTPSampler.connect_timeout\"></stringProp>\n");
        output.append("          <stringProp name=\"HTTPSampler.response_timeout\"></stringProp>\n");
        return output.append("        </HTTPSamplerProxy>\n        <hashTree/>\n").toString();
    }

    private JmeterTarget jmeterTarget(String url) {
        try {
            URI parsed = URI.create(url);
            String path = parsed.getRawPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
            if (parsed.getRawQuery() != null) {
                path += "?" + parsed.getRawQuery();
            }
            return new JmeterTarget(
                    parsed.getScheme() == null ? "" : parsed.getScheme(),
                    parsed.getHost() == null ? "" : parsed.getHost(),
                    parsed.getPort() < 0 ? "" : Integer.toString(parsed.getPort()), path);
        } catch (IllegalArgumentException exception) {
            throw new ArtifactOperationException("performance_export_jmeter_url_invalid",
                    "JMeter export requires a valid HTTP workload URL");
        }
    }

    private String requestBody(JsonNode request) {
        if (!request.has("body") || request.get("body").isNull()) {
            return "";
        }
        JsonNode body = request.get("body");
        return body.isTextual() ? body.asText() : body.toString();
    }

    private String renderBody(String body) {
        StringBuilder output = new StringBuilder();
        if (body.isEmpty()) {
            output.append("          <boolProp name=\"HTTPSampler.postBodyRaw\">false</boolProp>\n");
            output.append("          <elementProp name=\"HTTPsampler.Arguments\" ");
            output.append("elementType=\"Arguments\">\n");
            output.append("            <collectionProp name=\"Arguments.arguments\"/>\n");
            return output.append("          </elementProp>\n").toString();
        }
        output.append("          <boolProp name=\"HTTPSampler.postBodyRaw\">true</boolProp>\n");
        output.append("          <elementProp name=\"HTTPsampler.Arguments\" ");
        output.append("elementType=\"Arguments\">\n");
        output.append("            <collectionProp name=\"Arguments.arguments\">\n");
        output.append("              <elementProp name=\"\" elementType=\"HTTPArgument\">\n");
        output.append("                <boolProp name=\"HTTPArgument.always_encode\">false</boolProp>\n");
        output.append("                <stringProp name=\"Argument.value\">").append(xml(body))
                .append("</stringProp>\n");
        output.append("                <stringProp name=\"Argument.metadata\">=</stringProp>\n");
        output.append("              </elementProp>\n            </collectionProp>\n");
        return output.append("          </elementProp>\n").toString();
    }

    private String threadGroup(int concurrency, int ramp, int duration) {
        return "      <ThreadGroup guiclass=\"ThreadGroupGui\" testclass=\"ThreadGroup\" "
                + "testname=\"Performance Threads\" enabled=\"true\">\n"
                + "        <stringProp name=\"ThreadGroup.on_sample_error\">continue</stringProp>\n"
                + "        <elementProp name=\"ThreadGroup.main_controller\" elementType=\"LoopController\">\n"
                + "          <boolProp name=\"LoopController.continue_forever\">false</boolProp>\n"
                + "          <intProp name=\"LoopController.loops\">-1</intProp>\n"
                + "        </elementProp>\n"
                + "        <stringProp name=\"ThreadGroup.num_threads\">" + concurrency + "</stringProp>\n"
                + "        <stringProp name=\"ThreadGroup.ramp_time\">" + ramp + "</stringProp>\n"
                + "        <boolProp name=\"ThreadGroup.scheduler\">true</boolProp>\n"
                + "        <stringProp name=\"ThreadGroup.duration\">" + duration + "</stringProp>\n"
                + "        <stringProp name=\"ThreadGroup.delay\">0</stringProp>\n"
                + "        <boolProp name=\"ThreadGroup.same_user_on_next_iteration\">true</boolProp>\n"
                + "      </ThreadGroup>\n      <hashTree>\n";
    }

    private String renderHeaders(Map<String, String> headers) {
        if (headers.isEmpty()) {
            return "";
        }
        StringBuilder items = new StringBuilder();
        items.append("        <HeaderManager guiclass=\"HeaderPanel\" ")
                .append("testclass=\"HeaderManager\" testname=\"HTTP Header Manager\" ")
                .append("enabled=\"true\">\n");
        items.append("          <collectionProp name=\"HeaderManager.headers\">\n");
        headers.forEach((name, value) -> items.append("              <elementProp name=\"").append(xml(name))
                .append("\" elementType=\"Header\">\n                <stringProp name=\"Header.name\">")
                .append(xml(name)).append("</stringProp>\n                <stringProp name=\"Header.value\">")
                .append(xml(value)).append("</stringProp>\n              </elementProp>\n"));
        return items.append("          </collectionProp>\n        </HeaderManager>\n        <hashTree/>\n").toString();
    }

    private static boolean isJmeter(JsonNode contract) {
        return "jmeter".equals(contract.path("workloadProvider").path("type").asText());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String safeSegment(String value) {
        String result = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return result.isBlank() ? "plan" : result;
    }

    private static String pretty(JsonNode node) {
        return node.toPrettyString() + "\n";
    }

    private static void writeText(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ArtifactOperationException("execution_export_write_failed",
                    "Execution export could not be persisted");
        }
    }

    private static String runnerScript() {
        return String.join("\n", List.of(
                "#!/usr/bin/env node",
                "const fs=require('node:fs');",
                "const path=require('node:path');",
                "const cp=require('node:child_process');",
                "const a=process.argv.slice(2),v={};",
                "for(let i=0;i<a.length;i+=1){if(a[i].startsWith('--'))v[a[i].slice(2)]=a[++i];}",
                "function fail(reason){process.stderr.write(JSON.stringify({status:'blocked',reasonCode:reason})+'\\n');process.exit(1);}",
                "if(!v.bundle||!fs.existsSync(v.bundle))fail('performance_export_bundle_missing');",
                "const b=JSON.parse(fs.readFileSync(v.bundle,'utf8'));",
                "for(const p of (b.plans||[])){",
                "  const r=p.exportedArtifacts&&p.exportedArtifacts.jmxPathRel;if(!r)continue;",
                "  const j=path.resolve(v.exportDir||path.dirname(v.bundle),r);",
                "  if(!fs.existsSync(j))fail('performance_export_jmeter_artifact_missing');",
                "  const d=process.env.MCP_JAVA_DEV_TOOLS_JMETER_HOME;",
                "  let exe='jmeter';",
                "  if(d)exe=path.join(d,'bin',process.platform==='win32'?'jmeter.bat':'jmeter');",
                "  const out=path.join(path.dirname(j),p.planName+'.jtl');",
                "  const log=path.join(path.dirname(j),p.planName+'.log');",
                "  const x=cp.spawnSync(exe,['-n','-t',j,'-l',out,'-j',log],{stdio:'inherit',shell:process.platform==='win32'});",
                "  if(x.error||x.status!==0)fail('performance_export_jmeter_failed');",
                "}",
                "process.stdout.write(JSON.stringify({status:'ok',exportId:b.exportId,suiteType:b.suiteType})+'\\n');",
                ""));
    }

    private record JmeterTarget(String protocol, String domain, String port, String path) {
    }
}
