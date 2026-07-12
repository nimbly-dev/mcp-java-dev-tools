const fs = require("node:fs");
const path = require("node:path");

const source = path.join(
  __dirname,
  "..",
  "tools",
  "features",
  "artifact-management",
  "state-store",
  "migrations",
);
const target = path.join(
  __dirname,
  "..",
  "dist",
  "tools",
  "features",
  "artifact-management",
  "state-store",
  "migrations",
);

fs.mkdirSync(target, { recursive: true });
fs.cpSync(source, target, { recursive: true });
