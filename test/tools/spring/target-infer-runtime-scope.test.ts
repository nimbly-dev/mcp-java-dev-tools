const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const { inferTargets } = require("@tools-feature-route-synthesis");
const { discoverClassMethods } = require("@tools-feature-route-synthesis");

async function withTempDir(run: (dir: string) => Promise<void>) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "target-infer-runtime-scope-"));
  try {
    await run(dir);
  } finally {
    await fs.rm(dir, { recursive: true, force: true });
  }
}

test("inferTargets excludes src/test/java from runtime candidate pool", async () => {
  await withTempDir(async (dir: string) => {
    const runtimeFile = path.join(
      dir,
      "src",
      "main",
      "java",
      "com",
      "example",
      "CatalogShoeController.java",
    );
    const testFile = path.join(
      dir,
      "src",
      "test",
      "java",
      "com",
      "example",
      "CatalogShoeController.java",
    );

    await fs.mkdir(path.dirname(runtimeFile), { recursive: true });
    await fs.mkdir(path.dirname(testFile), { recursive: true });

    await fs.writeFile(
      runtimeFile,
      [
        "package com.example;",
        "public class CatalogShoeController {",
        "  public void runtimeOnly() {",
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );
    await fs.writeFile(
      testFile,
      [
        "package com.example;",
        "public class CatalogShoeController {",
        "  public void testOnly() {",
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );

    const out = await inferTargets({
      rootAbs: dir,
      classHint: "com.example.CatalogShoeController",
      maxCandidates: 10,
    });

    assert.equal(out.scannedJavaFiles, 1);
    assert.equal(out.candidates.length, 1);
    assert.equal(out.candidates[0]?.methodName, "runtimeOnly");
    assert.equal(out.candidates[0]?.file, runtimeFile);
  });
});

test("inferTargets includes generated-main runtime roots", async () => {
  await withTempDir(async (dir: string) => {
    const generatedMainFile = path.join(
      dir,
      "target",
      "generated-sources",
      "openapi",
      "src",
      "main",
      "java",
      "com",
      "generated",
      "GeneratedCatalogController.java",
    );

    await fs.mkdir(path.dirname(generatedMainFile), { recursive: true });
    await fs.writeFile(
      generatedMainFile,
      [
        "package com.generated;",
        "public class GeneratedCatalogController {",
        "  public void generatedEndpoint() {",
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );

    const out = await inferTargets({
      rootAbs: dir,
      classHint: "com.generated.GeneratedCatalogController",
      methodHint: "generatedEndpoint",
    });

    assert.equal(out.scannedJavaFiles, 1);
    assert.equal(out.candidates.length, 1);
    assert.equal(out.candidates[0]?.file, generatedMainFile);
    assert.equal(out.candidates[0]?.fqcn, "com.generated.GeneratedCatalogController");
  });
});

test("discoverClassMethods captures multiline Spring controller signatures deterministically", async () => {
  await withTempDir(async (dir: string) => {
    const controllerFile = path.join(
      dir,
      "src",
      "main",
      "java",
      "com",
      "example",
      "CatalogShoeController.java",
    );
    await fs.mkdir(path.dirname(controllerFile), { recursive: true });
    await fs.writeFile(
      controllerFile,
      [
        "package com.example;",
        "public class CatalogShoeController {",
        "  public String listCatalogShoes(",
        "      String brand,",
        "      String gender,",
        "      Integer page,",
        "      Integer size",
        "  ) {",
        "    return brand + gender + page + size;",
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );

    const out = await discoverClassMethods({
      rootAbs: dir,
      classHint: "com.example.CatalogShoeController",
    });

    assert.equal(out.matchMode, "exact");
    assert.equal(out.classes.length, 1);
    assert.equal(out.classes[0].methods.length, 1);
    assert.equal(out.classes[0].methods[0].methodName, "listCatalogShoes");
    assert.equal(out.classes[0].methods[0].startLine, 3);
    assert.equal(out.classes[0].methods[0].firstExecutableLine, 9);
  });
});

test("inferTargets prefers executable line while preserving declaration line metadata", async () => {
  await withTempDir(async (dir: string) => {
    const controllerFile = path.join(
      dir,
      "src",
      "main",
      "java",
      "com",
      "example",
      "CatalogShoeController.java",
    );
    await fs.mkdir(path.dirname(controllerFile), { recursive: true });
    await fs.writeFile(
      controllerFile,
      [
        "package com.example;",
        "public class CatalogShoeController {",
        "  public String listCatalogShoes(",
        "      String brand",
        "  ) {",
        "    return brand;",
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );

    const out = await inferTargets({
      rootAbs: dir,
      classHint: "com.example.CatalogShoeController",
      methodHint: "listCatalogShoes",
      maxCandidates: 5,
    });

    assert.equal(out.candidates.length, 1);
    assert.equal(out.candidates[0].line, 6);
    assert.equal(out.candidates[0].declarationLine, 3);
    assert.equal(out.candidates[0].firstExecutableLine, 6);
  });
});

test("inferTargets honors explicit lineHint for declaration line disambiguation", async () => {
  await withTempDir(async (dir: string) => {
    const controllerFile = path.join(
      dir,
      "src",
      "main",
      "java",
      "com",
      "example",
      "CatalogShoeController.java",
    );
    await fs.mkdir(path.dirname(controllerFile), { recursive: true });
    await fs.writeFile(
      controllerFile,
      [
        "package com.example;",
        "public class CatalogShoeController {",
        "  public String listCatalogShoes(",
        "      String brand",
        "  ) {",
        "    return brand;",
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );

    const out = await inferTargets({
      rootAbs: dir,
      classHint: "com.example.CatalogShoeController",
      methodHint: "listCatalogShoes",
      lineHint: 3,
      maxCandidates: 5,
    });

    assert.equal(out.candidates.length, 1);
    assert.equal(out.candidates[0].declarationLine, 3);
    assert.equal(out.candidates[0].line, 6);
  });
});

test("inferTargets resolves inherited target method when sibling source root is provided", async () => {
  await withTempDir(async (dir: string) => {
    const childFile = path.join(
      dir,
      "service-module",
      "src",
      "main",
      "java",
      "com",
      "example",
      "app",
      "controller",
      "AppController.java",
    );
    const parentFile = path.join(
      dir,
      "core-module",
      "src",
      "main",
      "java",
      "com",
      "example",
      "core",
      "controller",
      "AbstractAppController.java",
    );
    await fs.mkdir(path.dirname(childFile), { recursive: true });
    await fs.mkdir(path.dirname(parentFile), { recursive: true });
    await fs.writeFile(
      childFile,
      [
        "package com.example.app.controller;",
        "import com.example.core.controller.AbstractAppController;",
        "public class AppController extends AbstractAppController {",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );
    await fs.writeFile(
      parentFile,
      [
        "package com.example.core.controller;",
        "public abstract class AbstractAppController {",
        "  public String getData() {",
        '    return "ok";',
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );

    const withoutAdditionalRoots = await inferTargets({
      rootAbs: path.join(dir, "service-module"),
      classHint: "com.example.app.controller.AppController",
      methodHint: "getData",
      maxCandidates: 5,
    });
    assert.equal(withoutAdditionalRoots.candidates.length, 0);

    const withAdditionalRoots = await inferTargets({
      rootAbs: path.join(dir, "service-module"),
      additionalRootsAbs: [path.join(dir, "core-module", "src", "main", "java")],
      classHint: "com.example.core.controller.AbstractAppController",
      methodHint: "getData",
      maxCandidates: 5,
    });
    assert.equal(withAdditionalRoots.candidates.length, 1);
    assert.equal(withAdditionalRoots.candidates[0].methodName, "getData");
  });
});
