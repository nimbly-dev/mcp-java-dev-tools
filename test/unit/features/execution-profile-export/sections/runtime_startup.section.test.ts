const assert = require("node:assert/strict");
const test = require("node:test");

const {
  buildPs1RuntimeStartupSection,
} = require("../../../../../tools/features/execution-profile-export/sections/ps1/runtime_startup.section");
const {
  buildShRuntimeStartupSection,
} = require("../../../../../tools/features/execution-profile-export/sections/sh/runtime_startup.section");

const terminalWorkspace = {
  runtimeContexts: [
    {
      name: "dynamic-local",
      mode: "terminal",
      autoStart: true,
      autoStopOnFinish: true,
      startups: [
        {
          name: "producer-service",
          command: "java",
          args: ["-jar", "producer-service/app.jar", "--server.port=18080"],
          appdir: ".",
        },
      ],
    },
  ],
};

test("[UT][execution-profile-export][runtime-startup] PowerShell starts terminal runtimes asynchronously and records the owned PID", () => {
  const section = buildPs1RuntimeStartupSection({
    workspaceRootAbs: "C:/workspace",
    workspace: terminalWorkspace,
    runtimeContextName: "dynamic-local",
    includeRuntimeStartup: true,
  }).join("\n");

  assert.match(section, /Start-Process -FilePath 'java'/);
  assert.match(section, /-ArgumentList \$__mcpjvm_startup_args/);
  assert.match(section, /McpJvmOwnedRuntimeProcesses \+= \$__mcpjvm_started_process/);
  assert.match(section, /McpJvmStopOwnedRuntime = \$true/);
  assert.doesNotMatch(section, /& 'java' '-jar'/);
});

test("[UT][execution-profile-export][runtime-startup] shell starts terminal runtimes asynchronously and installs owned-process cleanup", () => {
  const section = buildShRuntimeStartupSection({
    workspaceRootAbs: "C:/workspace",
    workspace: terminalWorkspace,
    runtimeContextName: "dynamic-local",
    includeRuntimeStartup: true,
  }).join("\n");

  assert.match(section, /exec java -jar producer-service\/app\.jar --server\.port=18080/);
  assert.match(section, /runtime-R01\.log" 2>&1 &/);
  assert.match(section, /__MCPJVM_OWNED_RUNTIME_PIDS\+=/);
  assert.match(section, /kill "\$\{__mcpjvm_pid\}"/);
});
