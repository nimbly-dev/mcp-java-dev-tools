import * as fs from "node:fs/promises";
import type { Dirent } from "node:fs";
import * as path from "node:path";

const EXCLUDED_DIRS = new Set([
  ".git",
  "node_modules",
  "out",
  ".idea",
  ".vscode",
]);

const RUNTIME_SOURCE_MARKERS = [
  "src/main/java/",
  "target/generated-sources/openapi/src/main/java/",
  "target/generated-sources/src/main/java/",
  "build/generated/sources/annotationprocessor/java/main/",
];

function normalizePathForMatch(filePath: string): string {
  return filePath.replace(/\\/g, "/").toLowerCase();
}

function isRuntimeJavaFile(rootAbs: string, fileAbs: string): boolean {
  const normalizedRoot = normalizePathForMatch(path.resolve(rootAbs));
  const rootIsJavaSourceRoot =
    normalizedRoot.endsWith("/src/main/java") ||
    normalizedRoot.endsWith("/target/generated-sources/openapi/src/main/java") ||
    normalizedRoot.endsWith("/target/generated-sources/src/main/java") ||
    normalizedRoot.endsWith("/build/generated/sources/annotationprocessor/java/main");

  const rel = normalizePathForMatch(path.relative(rootAbs, fileAbs));
  if (rel.startsWith("../")) return false;
  if (rel.includes("src/test/java/")) return false;
  if (rootIsJavaSourceRoot) return true;
  return RUNTIME_SOURCE_MARKERS.some((marker) => rel.includes(marker));
}

async function queueGeneratedSourceRoots(
  queue: string[],
  baseAbs: string,
  dirName: string,
): Promise<void> {
  const candidates =
    dirName === "target"
      ? [
          path.join(baseAbs, "generated-sources", "openapi", "src", "main", "java"),
          path.join(baseAbs, "generated-sources", "src", "main", "java"),
        ]
      : [path.join(baseAbs, "generated", "sources", "annotationProcessor", "java", "main")];

  for (const candidate of candidates) {
    try {
      const stat = await fs.stat(candidate);
      if (stat.isDirectory()) queue.push(candidate);
    } catch {
      // ignore missing generated source roots
    }
  }
}

export type JavaMethod = {
  name: string;
  line: number;
  endLine: number;
  declarationLine: number;
  firstExecutableLine: number;
  signature: string;
};

export type JavaFileIndex = {
  fileAbs: string;
  packageName?: string;
  className?: string;
  methods: JavaMethod[];
  text: string;
};

const CONTROL_FLOW_TOKENS = new Set([
  "if",
  "for",
  "while",
  "switch",
  "catch",
  "try",
  "do",
  "else",
  "new",
  "return",
  "throw",
  "case",
  "default",
  "synchronized",
]);

function sanitizeForStructure(text: string): string {
  const out = Array.from(text);
  let i = 0;
  let inLineComment = false;
  let inBlockComment = false;
  let inString = false;
  let inChar = false;
  let escape = false;

  while (i < out.length) {
    const ch = out[i]!;
    const next = i + 1 < out.length ? out[i + 1]! : "";

    if (inLineComment) {
      if (ch !== "\n") out[i] = " ";
      if (ch === "\n") inLineComment = false;
      i += 1;
      continue;
    }

    if (inBlockComment) {
      if (ch !== "\n") out[i] = " ";
      if (ch === "*" && next === "/") {
        out[i] = " ";
        if (i + 1 < out.length) out[i + 1] = " ";
        inBlockComment = false;
        i += 2;
        continue;
      }
      i += 1;
      continue;
    }

    if (inString) {
      if (ch !== "\n") out[i] = " ";
      if (!escape && ch === '"') inString = false;
      escape = !escape && ch === "\\";
      i += 1;
      continue;
    }

    if (inChar) {
      if (ch !== "\n") out[i] = " ";
      if (!escape && ch === "'") inChar = false;
      escape = !escape && ch === "\\";
      i += 1;
      continue;
    }

    if (ch === "/" && next === "/") {
      out[i] = " ";
      if (i + 1 < out.length) out[i + 1] = " ";
      inLineComment = true;
      i += 2;
      continue;
    }

    if (ch === "/" && next === "*") {
      out[i] = " ";
      if (i + 1 < out.length) out[i + 1] = " ";
      inBlockComment = true;
      i += 2;
      continue;
    }

    if (ch === '"') {
      out[i] = " ";
      inString = true;
      escape = false;
      i += 1;
      continue;
    }

    if (ch === "'") {
      out[i] = " ";
      inChar = true;
      escape = false;
      i += 1;
      continue;
    }

    i += 1;
  }

  return out.join("");
}

function buildLineStarts(text: string): number[] {
  const starts = [0];
  for (let i = 0; i < text.length; i++) {
    if (text[i] === "\n") starts.push(i + 1);
  }
  return starts;
}

function lineNumberAt(index: number, lineStarts: number[]): number {
  let lo = 0;
  let hi = lineStarts.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    const start = lineStarts[mid]!;
    const next = mid + 1 < lineStarts.length ? lineStarts[mid + 1]! : Number.MAX_SAFE_INTEGER;
    if (index < start) {
      hi = mid - 1;
    } else if (index >= next) {
      lo = mid + 1;
    } else {
      return mid + 1;
    }
  }
  return 1;
}

function isIdentifierChar(ch: string): boolean {
  return /[A-Za-z0-9_$]/.test(ch);
}

function readIdentifierBackward(source: string, index: number): { value: string; start: number } | null {
  let end = index;
  while (end >= 0 && /\s/.test(source[end] ?? "")) end -= 1;
  if (end < 0 || !isIdentifierChar(source[end]!)) return null;
  let start = end;
  while (start >= 0 && isIdentifierChar(source[start]!)) start -= 1;
  start += 1;
  return { value: source.slice(start, end + 1), start };
}

function findMatchingParenBackward(source: string, closeParenIndex: number): number | null {
  let depth = 0;
  for (let i = closeParenIndex; i >= 0; i--) {
    const ch = source[i]!;
    if (ch === ")") depth += 1;
    else if (ch === "(") {
      depth -= 1;
      if (depth === 0) return i;
    }
  }
  return null;
}

function findDeclarationStart(source: string, beforeIndex: number): number {
  for (let i = beforeIndex; i >= 0; i--) {
    const ch = source[i]!;
    if (ch === ";" || ch === "{" || ch === "}") {
      return i + 1;
    }
  }
  return 0;
}

function normalizeSignature(raw: string): string {
  return raw.replace(/\s+/g, " ").trim();
}

function findFirstExecutableLine(args: {
  methodBodyStartIndex: number;
  methodBodyEndIndex: number;
  text: string;
  sanitized: string;
  lineStarts: number[];
  declarationLine: number;
}): number {
  if (args.methodBodyEndIndex < args.methodBodyStartIndex) {
    return args.declarationLine;
  }
  const startLine = lineNumberAt(args.methodBodyStartIndex, args.lineStarts);
  const endLine = lineNumberAt(args.methodBodyEndIndex, args.lineStarts);

  for (let line = startLine; line <= endLine; line++) {
    const lineStart = args.lineStarts[line - 1]!;
    const nextLineStart = line < args.lineStarts.length ? args.lineStarts[line]! : args.text.length;
    const segmentStart = line === startLine ? Math.max(lineStart, args.methodBodyStartIndex) : lineStart;
    const segmentEnd = line === endLine ? Math.min(nextLineStart, args.methodBodyEndIndex + 1) : nextLineStart;
    const sanitizedSlice = args.sanitized.slice(segmentStart, segmentEnd).trim();
    if (!sanitizedSlice) continue;
    if (sanitizedSlice === "{" || sanitizedSlice === "}") continue;
    if (sanitizedSlice.startsWith("@")) continue;
    return line;
  }
  return args.declarationLine;
}

function extractMethods(text: string): JavaMethod[] {
  const sanitized = sanitizeForStructure(text);
  const lineStarts = buildLineStarts(text);
  const methods: JavaMethod[] = [];
  const braceStack: number[] = [];
  const bracePairs = new Map<number, number>();

  for (let i = 0; i < sanitized.length; i++) {
    const ch = sanitized[i]!;
    if (ch === "{") {
      braceStack.push(i);
    } else if (ch === "}") {
      const open = braceStack.pop();
      if (typeof open === "number") bracePairs.set(open, i);
    }
  }

  const opens = Array.from(bracePairs.keys()).sort((a, b) => a - b);
  for (const openBraceIndex of opens) {
    let cursor = openBraceIndex - 1;
    while (cursor >= 0 && /\s/.test(sanitized[cursor] ?? "")) cursor -= 1;
    if (cursor < 0 || sanitized[cursor] !== ")") continue;

    const openParenIndex = findMatchingParenBackward(sanitized, cursor);
    if (typeof openParenIndex !== "number") continue;

    const methodNameRef = readIdentifierBackward(sanitized, openParenIndex - 1);
    if (!methodNameRef) continue;
    const methodName = methodNameRef.value;
    if (CONTROL_FLOW_TOKENS.has(methodName)) continue;

    const previousToken = readIdentifierBackward(sanitized, methodNameRef.start - 1)?.value ?? "";
    if (previousToken === "new") continue;

    const closeBraceIndex = bracePairs.get(openBraceIndex);
    if (typeof closeBraceIndex !== "number" || closeBraceIndex <= openBraceIndex) continue;

    const declarationStart = findDeclarationStart(sanitized, methodNameRef.start - 1);
    const signatureRaw = text.slice(declarationStart, openBraceIndex + 1);
    const signature = normalizeSignature(signatureRaw);
    if (!signature) continue;

    const declarationLine = lineNumberAt(methodNameRef.start, lineStarts);
    const endLine = lineNumberAt(closeBraceIndex, lineStarts);
    const firstExecutableLine = findFirstExecutableLine({
      methodBodyStartIndex: openBraceIndex + 1,
      methodBodyEndIndex: closeBraceIndex - 1,
      text,
      sanitized,
      lineStarts,
      declarationLine,
    });

    methods.push({
      name: methodName,
      line: declarationLine,
      declarationLine,
      firstExecutableLine,
      endLine,
      signature,
    });
  }

  methods.sort((a, b) => {
    if (a.line !== b.line) return a.line - b.line;
    if (a.name !== b.name) return a.name.localeCompare(b.name);
    return a.endLine - b.endLine;
  });

  return methods;
}

async function walkJavaFiles(
  rootAbs: string,
  maxFiles: number,
  classHint?: string,
): Promise<string[]> {
  const prioritized: string[] = [];
  const fallback: string[] = [];
  const queue: string[] = [rootAbs];
  const classHintLower = classHint?.toLowerCase();

  while (queue.length > 0 && prioritized.length + fallback.length < maxFiles) {
    const dir = queue.shift()!;
    let entries: Dirent[];
    try {
      entries = await fs.readdir(dir, { withFileTypes: true });
    } catch {
      continue;
    }

    for (const e of entries) {
      if (prioritized.length + fallback.length >= maxFiles) break;
      const abs = path.join(dir, e.name);
      if (e.isDirectory()) {
        if (e.name === "target" || e.name === "build") {
          await queueGeneratedSourceRoots(queue, abs, e.name);
          continue;
        }
        if (EXCLUDED_DIRS.has(e.name)) continue;
        queue.push(abs);
        continue;
      }
      if (!e.isFile() || !e.name.endsWith(".java")) continue;
      if (!isRuntimeJavaFile(rootAbs, abs)) continue;
      if (classHintLower) {
        const basenameLower = e.name.toLowerCase();
        if (basenameLower.includes(classHintLower)) {
          prioritized.push(abs);
          continue;
        }
      }
      fallback.push(abs);
    }
  }
  return [...prioritized, ...fallback];
}

export async function buildJavaIndex(args: {
  rootAbs: string;
  additionalRootsAbs?: string[];
  maxFiles?: number;
  classHint?: string;
}): Promise<JavaFileIndex[]> {
  const maxFiles = Math.max(20, Math.min(5_000, args.maxFiles ?? 1_000));
  const roots = [
    path.resolve(args.rootAbs),
    ...(args.additionalRootsAbs ?? []).map((value) => path.resolve(value)),
  ].filter((value, index, all) => all.indexOf(value) === index);
  const files: string[] = [];
  for (const root of roots) {
    const remaining = maxFiles - files.length;
    if (remaining <= 0) break;
    const discovered = await walkJavaFiles(root, remaining, args.classHint);
    for (const fileAbs of discovered) {
      if (files.indexOf(fileAbs) < 0) {
        files.push(fileAbs);
      }
      if (files.length >= maxFiles) break;
    }
  }
  const results: JavaFileIndex[] = [];

  for (const fileAbs of files) {
    let text = "";
    try {
      text = await fs.readFile(fileAbs, "utf8");
    } catch {
      continue;
    }

    const packageMatch = text.match(/^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*;/m);
    const classMatch = text.match(
      /^\s*(?:public\s+)?(?:abstract\s+|final\s+)?(?:class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)\b/m,
    );

    const methods = extractMethods(text);

    const entry: JavaFileIndex = {
      fileAbs,
      methods,
      text,
    };
    if (packageMatch?.[1]) entry.packageName = packageMatch[1];
    if (classMatch?.[1]) entry.className = classMatch[1];
    results.push(entry);
  }

  return results;
}
