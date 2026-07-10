const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..");
const featuresRoot = path.join(root, "tools", "features");
const failures = [];

function walk(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const absolute = path.join(directory, entry.name);
    return entry.isDirectory() ? walk(absolute) : [absolute];
  });
}

const forbiddenGlobal = [
  path.join(root, "tools", "shared"),
  path.join(root, "tools", "utils", "shared"),
];
for (const directory of forbiddenGlobal) {
  if (fs.existsSync(directory))
    failures.push(`anonymous global shared directory exists: ${directory}`);
}

for (const feature of fs
  .readdirSync(featuresRoot, { withFileTypes: true })
  .filter((entry) => entry.isDirectory())) {
  const featureRoot = path.join(featuresRoot, feature.name);
  if (!fs.existsSync(path.join(featureRoot, "index.ts")))
    failures.push(`${feature.name}: missing public index.ts`);
  if (!fs.existsSync(path.join(featureRoot, "actions", "index.ts")))
    failures.push(`${feature.name}: missing actions/index.ts`);
  for (const file of walk(featureRoot).filter((candidate) => candidate.endsWith(".ts"))) {
    const source = fs.readFileSync(file, "utf8");
    const imports = source.match(/(?:from|require\()\s*["']([^"']+)["']/g) ?? [];
    for (const statement of imports) {
      const match = statement.match(/["']([^"']+)["']/);
      if (!match) continue;
      const imported = match[1].replaceAll("\\", "/");
      const foreignShared = imported.match(/tools-feature-([^/]+)\/(.*\/)?shared\//);
      if (foreignShared && foreignShared[1] !== feature.name) {
        failures.push(
          `${path.relative(root, file)} imports foreign Feature-private shared code: ${imported}`,
        );
      }
    }
  }
}

if (failures.length > 0) {
  console.error(failures.join("\n"));
  process.exitCode = 1;
} else {
  console.log("Feature Module boundary check passed.");
}
