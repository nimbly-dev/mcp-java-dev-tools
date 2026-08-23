const fs = require("node:fs");
const path = require("node:path");
const { dispatchExecutionProfileExportAction } = require("@tools-export-execution-profile");

const workspaceRootAbs = path.resolve(process.argv[2]);

async function main() {
  const result = await dispatchExecutionProfileExportAction({
    workspaceRootAbs,
    projectName: "demo",
    executionProfile: "load-suite",
    exportId: "20260101-000000-load-suite",
    mode: "ps1",
  });
  const exportDirAbs = String(result.structuredContent.exportDirAbs || "");
  if (!exportDirAbs) {
    process.stdout.write(JSON.stringify({ status: result.structuredContent.status, structuredContent: result.structuredContent }) + "\n");
    return;
  }
  const files = {};
  function collect(current) {
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const absolute = path.join(current, entry.name);
      if (entry.isDirectory()) collect(absolute);
      else files[path.relative(exportDirAbs, absolute).replaceAll(path.sep, "/")] = fs.readFileSync(absolute, "utf8");
    }
  }
  collect(exportDirAbs);
  process.stdout.write(JSON.stringify({ status: result.structuredContent.status, files }) + "\n");
}

main().catch((error) => {
  process.stderr.write(`${error.stack || error}\n`);
  process.exitCode = 1;
});
