import { escapeXml } from "@tools-performance-workload-jmeter/common";
import type {
  JmeterGeneratedHttpLoadModel,
  JmeterGeneratedHttpRequest,
} from "@tools-performance-workload-jmeter/models/jmeter_workload_provider.model";

function renderHeaderManager(headers: Record<string, string> | undefined): string {
  if (!headers || Object.keys(headers).length === 0) {
    return "";
  }
  const items = Object.entries(headers)
    .map(
      ([name, value]) => `              <elementProp name="${escapeXml(name)}" elementType="Header">
                <stringProp name="Header.name">${escapeXml(name)}</stringProp>
                <stringProp name="Header.value">${escapeXml(value)}</stringProp>
              </elementProp>`,
    )
    .join("\n");
  return `        <HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="HTTP Header Manager" enabled="true">
          <collectionProp name="HeaderManager.headers">
${items}
          </collectionProp>
        </HeaderManager>
        <hashTree/>`;
}

export function renderGeneratedHttpJmx(args: {
  request: JmeterGeneratedHttpRequest;
  loadModel: JmeterGeneratedHttpLoadModel;
  planName: string;
}): string {
  const serializedBody =
    typeof args.request.body === "undefined"
      ? ""
      : typeof args.request.body === "string"
        ? args.request.body
        : JSON.stringify(args.request.body);
  const hasBody = serializedBody.length > 0;
  const timeoutMs =
    typeof args.request.timeoutMs === "number" && args.request.timeoutMs > 0
      ? String(args.request.timeoutMs)
      : "";
  const bodyBlock = hasBody
    ? `          <boolProp name="HTTPSampler.postBodyRaw">true</boolProp>
          <elementProp name="HTTPsampler.Arguments" elementType="Arguments">
            <collectionProp name="Arguments.arguments">
              <elementProp name="" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">false</boolProp>
                <stringProp name="Argument.value">${escapeXml(serializedBody)}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
              </elementProp>
            </collectionProp>
          </elementProp>`
    : `          <boolProp name="HTTPSampler.postBodyRaw">false</boolProp>
          <elementProp name="HTTPsampler.Arguments" elementType="Arguments">
            <collectionProp name="Arguments.arguments"/>
          </elementProp>`;

  return `<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3">
  <hashTree>
    <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="${escapeXml(args.planName)}" enabled="true">
      <stringProp name="TestPlan.comments"></stringProp>
      <boolProp name="TestPlan.functional_mode">false</boolProp>
      <boolProp name="TestPlan.tearDown_on_shutdown">true</boolProp>
      <boolProp name="TestPlan.serialize_threadgroups">false</boolProp>
      <elementProp name="TestPlan.user_defined_variables" elementType="Arguments" guiclass="ArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
        <collectionProp name="Arguments.arguments"/>
      </elementProp>
      <stringProp name="TestPlan.user_define_classpath"></stringProp>
    </TestPlan>
    <hashTree>
      <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="Performance Threads" enabled="true">
        <stringProp name="ThreadGroup.on_sample_error">continue</stringProp>
        <elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController" testname="Loop Controller" enabled="true">
          <boolProp name="LoopController.continue_forever">false</boolProp>
          <intProp name="LoopController.loops">-1</intProp>
        </elementProp>
        <stringProp name="ThreadGroup.num_threads">${String(args.loadModel.concurrency)}</stringProp>
        <stringProp name="ThreadGroup.ramp_time">${String(args.loadModel.rampUpSeconds)}</stringProp>
        <boolProp name="ThreadGroup.scheduler">true</boolProp>
        <stringProp name="ThreadGroup.duration">${String(args.loadModel.durationSeconds)}</stringProp>
        <stringProp name="ThreadGroup.delay">0</stringProp>
        <boolProp name="ThreadGroup.same_user_on_next_iteration">true</boolProp>
      </ThreadGroup>
      <hashTree>
${renderHeaderManager(args.request.headers)}
        <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="${escapeXml(args.request.method)} ${escapeXml(args.request.url)}" enabled="true">
${bodyBlock}
          <stringProp name="HTTPSampler.domain"></stringProp>
          <stringProp name="HTTPSampler.port"></stringProp>
          <stringProp name="HTTPSampler.protocol"></stringProp>
          <stringProp name="HTTPSampler.contentEncoding"></stringProp>
          <stringProp name="HTTPSampler.path">${escapeXml(args.request.url)}</stringProp>
          <stringProp name="HTTPSampler.method">${escapeXml(args.request.method)}</stringProp>
          <boolProp name="HTTPSampler.follow_redirects">true</boolProp>
          <boolProp name="HTTPSampler.auto_redirects">false</boolProp>
          <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
          <boolProp name="HTTPSampler.DO_MULTIPART_POST">false</boolProp>
          <stringProp name="HTTPSampler.embedded_url_re"></stringProp>
          <stringProp name="HTTPSampler.connect_timeout">${timeoutMs}</stringProp>
          <stringProp name="HTTPSampler.response_timeout">${timeoutMs}</stringProp>
        </HTTPSamplerProxy>
        <hashTree/>
      </hashTree>
    </hashTree>
  </hashTree>
</jmeterTestPlan>
`;
}

