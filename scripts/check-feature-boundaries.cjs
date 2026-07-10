const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(process.env.FEATURE_BOUNDARY_ROOT ?? path.join(__dirname, ".."));
const featuresRoot = path.join(root, "tools", "features");
const failures = [];

function walk(directory) {
  if (!fs.existsSync(directory)) return [];
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const absolute = path.join(directory, entry.name);
    return entry.isDirectory() ? walk(absolute) : [absolute];
  });
}

function normalize(value) {
  return value.replaceAll("\\", "/");
}

function relative(value) {
  return normalize(path.relative(root, value));
}

function featureFor(file) {
  const match = normalize(path.relative(featuresRoot, file)).match(/^([^/]+)(?:\/|$)/);
  return match?.[1];
}

function resolveFile(candidate) {
  const options = [candidate, `${candidate}.ts`, path.join(candidate, "index.ts")];
  return options.find((option) => fs.existsSync(option) && fs.statSync(option).isFile());
}

function resolveImport(importer, imported) {
  const source = normalize(imported);
  if (source.startsWith(".")) return resolveFile(path.resolve(path.dirname(importer), source));
  if (source === "@") return resolveFile(path.join(root, "tools", "transport", "tools-mcp-server", "src"));
  if (source.startsWith("@/")) {
    return resolveFile(path.join(root, "tools", "transport", "tools-mcp-server", "src", source.slice(2)));
  }
  const featureAlias = source.match(/^@(tools-feature|tools-export-execution-profile|tools-performance-workload-jmeter)(?:-([^/]+))?(?:\/(.*))?$/);
  if (featureAlias) {
    const featureName = featureAlias[2] ?? "execution-profile-export";
    const suffix = featureAlias[3];
    const featureRoot = path.join(featuresRoot, featureName);
    return resolveFile(suffix ? path.join(featureRoot, suffix) : path.join(featureRoot, "index.ts"));
  }
  if (source === "@tools-regression-suite" || source.startsWith("@tools-regression-suite/")) {
    const suffix = source.slice("@tools-regression-suite".length).replace(/^\//, "");
    return resolveFile(suffix ? path.join(featuresRoot, "regression-suite", suffix) : path.join(featuresRoot, "regression-suite", "index.ts"));
  }
  if (source.startsWith("@tools-regression-execution-plan-spec/")) {
    return resolveFile(path.join(root, "tools", "spec", "regression-execution-plan-spec", "src", source.slice("@tools-regression-execution-plan-spec/".length)));
  }
  if (source.startsWith("@tools-project-artifact-spec/")) {
    return resolveFile(path.join(root, "tools", "spec", "project-artifact-spec", "src", source.slice("@tools-project-artifact-spec/".length)));
  }
  return undefined;
}

function isUnder(file, directory) {
  const normalizedFile = normalize(path.resolve(file));
  const normalizedDirectory = normalize(path.resolve(directory));
  return normalizedFile === normalizedDirectory || normalizedFile.startsWith(`${normalizedDirectory}/`);
}

function isArtifactSpecRuntime(file) {
  const specRoot = path.join(root, "tools", "spec");
  if (!isUnder(file, specRoot)) return false;
  const normalized = relative(file);
  return normalized.includes("/regression-execution-plan-spec/") && !normalized.includes("/models/") && !normalized.endsWith("regression_artifact_paths.util.ts");
}

const forbiddenGlobal = [path.join(root, "tools", "shared"), path.join(root, "tools", "utils", "shared")];
for (const directory of forbiddenGlobal) {
  if (fs.existsSync(directory)) failures.push(`anonymous global shared directory exists: ${relative(directory)}`);
}

const tsconfigPath = path.join(root, "tsconfig.json");
if (fs.existsSync(tsconfigPath)) {
  const tsconfig = fs.readFileSync(tsconfigPath, "utf8");
  for (const match of tsconfig.matchAll(/"([^"\n]+\/\*)"\s*:\s*\[\s*"([^"]+)"/g)) {
    if (match[1].includes("tools-feature") || match[2].includes("tools/features")) {
      failures.push(`wildcard Feature alias is forbidden: ${match[1]} -> ${match[2]}`);
    }
  }
  if (tsconfig.includes("tools/features/regression-suite/shared/*")) {
    failures.push("regression Artifact Spec alias fallback into features/regression-suite/shared is forbidden");
  }
}

if (fs.existsSync(featuresRoot)) {
  for (const feature of fs.readdirSync(featuresRoot, { withFileTypes: true }).filter((entry) => entry.isDirectory())) {
    const featureRoot = path.join(featuresRoot, feature.name);
    if (!fs.existsSync(path.join(featureRoot, "index.ts"))) failures.push(`${feature.name}: missing public index.ts`);
    if (!fs.existsSync(path.join(featureRoot, "actions", "index.ts"))) failures.push(`${feature.name}: missing actions/index.ts`);
    for (const file of walk(featureRoot).filter((candidate) => candidate.endsWith(".ts"))) {
      const source = fs.readFileSync(file, "utf8");
      const imports = source.match(/(?:from|require\()\s*["'][^"']+["']/g) ?? [];
      for (const statement of imports) {
        const imported = statement.match(/["']([^"']+)["']/)?.[1];
        if (!imported) continue;
        const resolved = resolveImport(file, imported);
        const importedFeature = resolved ? featureFor(resolved) : undefined;
        if (resolved && isUnder(resolved, path.join(root, "tools", "transport", "tools-mcp-server", "src", "tools"))) {
          failures.push(`${relative(file)} imports Transport Adapter code: ${imported}`);
        }
        if (importedFeature && importedFeature !== feature.name && resolved) {
          const privatePath = normalize(path.relative(path.join(featuresRoot, importedFeature), resolved));
          if (privatePath.startsWith("actions/") || privatePath.startsWith("shared/")) {
            failures.push(`${relative(file)} imports foreign Feature-private code: ${imported}`);
          }
        }
        if (/^@(tools-feature|tools-export-execution-profile|tools-performance-workload-jmeter)-[^/]+\//.test(imported) || /^@tools-regression-suite\//.test(imported)) {
          failures.push(`${relative(file)} imports a Feature Module internal path: ${imported}`);
        }
      }
    }
  }
}

const specRoot = path.join(root, "tools", "spec");
for (const file of walk(specRoot).filter((candidate) => candidate.endsWith(".ts"))) {
  const source = fs.readFileSync(file, "utf8");
  const imports = source.match(/(?:from|require\()\s*["'][^"']+["']/g) ?? [];
  for (const statement of imports) {
    const imported = statement.match(/["']([^"']+)["']/)?.[1];
    const resolved = imported ? resolveImport(file, imported) : undefined;
    if (resolved && isUnder(resolved, featuresRoot)) {
      failures.push(`${relative(file)} imports runtime Feature Module code: ${imported}`);
    }
  }
}

if (failures.length > 0) {
  console.error(failures.join("\n"));
  process.exitCode = 1;
} else {
  console.log("Feature Module boundary check passed.");
}
