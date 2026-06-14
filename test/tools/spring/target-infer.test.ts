const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const { discoverClassMethods } = require("@/tools/core/route_synthesis/shared/target_inference.util");

async function withTempDir(run: (dir: string) => Promise<void>) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "target-infer-"));
  try {
    await run(dir);
  } finally {
    await fs.rm(dir, { recursive: true, force: true });
  }
}

test("discoverClassMethods returns exact class methods with start/end line spans", async () => {
  await withTempDir(async (dir: string) => {
    const javaFile = path.join(
      dir,
      "src",
      "main",
      "java",
      "com",
      "example",
      "CatalogEventController.java",
    );
    await fs.mkdir(path.dirname(javaFile), { recursive: true });
    await fs.writeFile(
      javaFile,
      [
        "package com.example;",
        "",
        "public class CatalogEventController {",
        "  public void alpha() {",
        "    if (true) {",
        '      System.out.println("x");',
        "    }",
        "  }",
        "",
        "  public String beta() {",
        '    return "ok";',
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );

    const out = await discoverClassMethods({
      rootAbs: dir,
      classHint: "CatalogEventController",
    });

    assert.equal(out.matchMode, "exact");
    assert.equal(out.classes.length, 1);
    assert.equal(out.classes[0].className, "CatalogEventController");
    assert.equal(out.classes[0].fqcn, "com.example.CatalogEventController");
    assert.deepEqual(out.classes[0].methods, [
      {
        methodName: "alpha",
        signature: "public void alpha() {",
        startLine: 4,
        endLine: 8,
        firstExecutableLine: 5,
        probeKey: "com.example.CatalogEventController#alpha",
      },
      {
        methodName: "beta",
        signature: "public String beta() {",
        startLine: 10,
        endLine: 12,
        firstExecutableLine: 11,
        probeKey: "com.example.CatalogEventController#beta",
      },
    ]);
  });
});

test("discoverClassMethods fails closed when class hint is not an exact class name/FQCN", async () => {
  await withTempDir(async (dir: string) => {
    const javaFile = path.join(dir, "src", "main", "java", "CatalogEventController.java");
    await fs.mkdir(path.dirname(javaFile), { recursive: true });
    await fs.writeFile(
      javaFile,
      ["public class CatalogEventController {", "  public void ping() {", "  }", "}", ""].join(
        "\n",
      ),
      "utf8",
    );

    const out = await discoverClassMethods({
      rootAbs: dir,
      classHint: "CatalogEvent",
    });

    assert.equal(out.matchMode, "none");
    assert.equal(out.classes.length, 0);
  });
});

test("discoverClassMethods returns all exact class-name matches for disambiguation", async () => {
  await withTempDir(async (dir: string) => {
    const classA = path.join(
      dir,
      "service-a",
      "src",
      "main",
      "java",
      "com",
      "a",
      "CatalogEventController.java",
    );
    const classB = path.join(
      dir,
      "service-b",
      "src",
      "main",
      "java",
      "com",
      "b",
      "CatalogEventController.java",
    );
    await fs.mkdir(path.dirname(classA), { recursive: true });
    await fs.mkdir(path.dirname(classB), { recursive: true });
    await fs.writeFile(
      classA,
      [
        "package com.a;",
        "public class CatalogEventController {",
        "  public void runA() {",
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );
    await fs.writeFile(
      classB,
      [
        "package com.b;",
        "public class CatalogEventController {",
        "  public void runB() {",
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );

    const out = await discoverClassMethods({
      rootAbs: dir,
      classHint: "CatalogEventController",
    });

    assert.equal(out.matchMode, "exact");
    assert.equal(out.classes.length, 2);
    assert.deepEqual(
      out.classes.map((c: { fqcn?: string }) => c.fqcn),
      ["com.a.CatalogEventController", "com.b.CatalogEventController"],
    );
  });
});

test("discoverClassMethods resolves a single exact FQCN when simple class name is duplicated", async () => {
  await withTempDir(async (dir: string) => {
    const classA = path.join(
      dir,
      "service-a",
      "src",
      "main",
      "java",
      "com",
      "a",
      "CatalogEventController.java",
    );
    const classB = path.join(
      dir,
      "service-b",
      "src",
      "main",
      "java",
      "com",
      "b",
      "CatalogEventController.java",
    );
    await fs.mkdir(path.dirname(classA), { recursive: true });
    await fs.mkdir(path.dirname(classB), { recursive: true });
    await fs.writeFile(
      classA,
      [
        "package com.a;",
        "public class CatalogEventController {",
        "  public void runA() {",
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );
    await fs.writeFile(
      classB,
      [
        "package com.b;",
        "public class CatalogEventController {",
        "  public void runB() {",
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );

    const out = await discoverClassMethods({
      rootAbs: dir,
      classHint: "com.b.CatalogEventController",
    });

    assert.equal(out.matchMode, "exact");
    assert.equal(out.classes.length, 1);
    assert.equal(out.classes[0].fqcn, "com.b.CatalogEventController");
    assert.equal(out.classes[0].methods[0].methodName, "runB");
  });
});

test("discoverClassMethods returns none when class is not found", async () => {
  await withTempDir(async (dir: string) => {
    const javaFile = path.join(dir, "src", "main", "java", "InventoryController.java");
    await fs.mkdir(path.dirname(javaFile), { recursive: true });
    await fs.writeFile(
      javaFile,
      ["public class InventoryController {", "  public void ping() {", "  }", "}", ""].join("\n"),
      "utf8",
    );

    const out = await discoverClassMethods({
      rootAbs: dir,
      classHint: "CatalogEventController",
    });

    assert.equal(out.matchMode, "none");
    assert.equal(out.classes.length, 0);
  });
});

test("discoverClassMethods keeps methods sorted by start line for controller-like classes", async () => {
  await withTempDir(async (dir: string) => {
    const javaFile = path.join(
      dir,
      "src",
      "main",
      "java",
      "com",
      "example",
      "CatalogEventController.java",
    );
    await fs.mkdir(path.dirname(javaFile), { recursive: true });
    await fs.writeFile(
      javaFile,
      [
        "package com.example;",
        "",
        "import org.springframework.web.bind.annotation.GetMapping;",
        "import org.springframework.web.bind.annotation.RestController;",
        "",
        "@RestController",
        "public class CatalogEventController {",
        '  @GetMapping("/health")',
        "  public String health() {",
        '    return "ok";',
        "  }",
        "",
        "  public String findById(String id) {",
        "    return id;",
        "  }",
        "",
        "  public String findById(String id, boolean activeOnly) {",
        "    if (activeOnly) {",
        "      return id;",
        "    }",
        '    return "inactive";',
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );

    const out = await discoverClassMethods({
      rootAbs: dir,
      classHint: "CatalogEventController",
    });

    assert.equal(out.matchMode, "exact");
    assert.deepEqual(
      out.classes[0].methods.map((m: { methodName: string; startLine: number }) => ({
        methodName: m.methodName,
        startLine: m.startLine,
      })),
      [
        { methodName: "health", startLine: 9 },
        { methodName: "findById", startLine: 13 },
        { methodName: "findById", startLine: 17 },
      ],
    );
    assert.equal(out.classes[0].methods[2].endLine, 22);
    assert.equal(out.classes[0].methods[0].firstExecutableLine, 10);
    assert.equal(out.classes[0].methods[1].firstExecutableLine, 14);
    assert.equal(out.classes[0].methods[2].firstExecutableLine, 18);
  });
});

test("query-style: broad class tokens fail closed in exact-only mode", async () => {
  await withTempDir(async (dir: string) => {
    const controllerFile = path.join(
      dir,
      "src",
      "main",
      "java",
      "com",
      "example",
      "CatalogEventController.java",
    );
    const healthFile = path.join(
      dir,
      "src",
      "main",
      "java",
      "com",
      "example",
      "HealthController.java",
    );
    await fs.mkdir(path.dirname(controllerFile), { recursive: true });
    await fs.writeFile(
      controllerFile,
      [
        "package com.example;",
        "public class CatalogEventController {",
        "  public void replayEvents() {",
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );
    await fs.writeFile(
      healthFile,
      [
        "package com.example;",
        "public class HealthController {",
        "  public String health() {",
        '    return "ok";',
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );

    const out = await discoverClassMethods({
      rootAbs: dir,
      classHint: "Controller",
    });

    assert.equal(out.matchMode, "none");
    assert.equal(out.classes.length, 0);
  });
});

test("query-style: full probe planning includes public and private methods in class inventory", async () => {
  await withTempDir(async (dir: string) => {
    const javaFile = path.join(
      dir,
      "src",
      "main",
      "java",
      "com",
      "example",
      "CatalogEventController.java",
    );
    await fs.mkdir(path.dirname(javaFile), { recursive: true });
    await fs.writeFile(
      javaFile,
      [
        "package com.example;",
        "public class CatalogEventController {",
        "  public String handleEvent(String eventId) {",
        "    String normalized = normalize(eventId);",
        "    return normalized;",
        "  }",
        "",
        "  private String normalize(String eventId) {",
        '    return eventId == null ? "" : eventId.trim();',
        "  }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );

    const out = await discoverClassMethods({
      rootAbs: dir,
      classHint: "CatalogEventController",
    });

    assert.equal(out.matchMode, "exact");
    assert.equal(out.classes.length, 1);
    assert.deepEqual(
      out.classes[0].methods.map((m: { methodName: string }) => m.methodName),
      ["handleEvent", "normalize"],
    );
  });
});
