const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const { isSpringProject } = require("@tools-spring-http/detect.util");

test("[UT][spring-http][detect][ok] spring detection finds nested module markers under multi-module root", async () => {
  const tempRoot = await fs.mkdtemp(path.join(os.tmpdir(), "spring-detect-"));
  try {
    const moduleDir = path.join(tempRoot, "services", "catalog-service");
    await fs.mkdir(moduleDir, { recursive: true });
    await fs.writeFile(
      path.join(moduleDir, "pom.xml"),
      "<project><dependency>org.springframework.boot:spring-boot-starter-web</dependency></project>",
      "utf8",
    );

    const detected = await isSpringProject(tempRoot);
    assert.equal(detected, true);
  } finally {
    await fs.rm(tempRoot, { recursive: true, force: true });
  }
});

test("[UT][spring-http][detect][ok] spring detection uses inferred controller annotation when build marker is absent", async () => {
  const tempRoot = await fs.mkdtemp(path.join(os.tmpdir(), "spring-detect-"));
  try {
    const controllerFile = path.join(
      tempRoot,
      "src",
      "main",
      "java",
      "com",
      "example",
      "CatalogController.java",
    );
    await fs.mkdir(path.dirname(controllerFile), { recursive: true });
    await fs.writeFile(
      controllerFile,
      "package com.example;\n@RestController\nclass CatalogController {}\n",
      "utf8",
    );

    const detected = await isSpringProject(tempRoot, {
      inferredTargetFileAbs: controllerFile,
    });
    assert.equal(detected, true);
  } finally {
    await fs.rm(tempRoot, { recursive: true, force: true });
  }
});
