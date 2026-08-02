import { promises as fs } from "node:fs";
import path from "node:path";

import type { SecurityAttackCategory, SecuritySeverity } from "@tools-security-execution-plan-spec";

type SecurityVersion = { major: number; minor: number; patch: number };

export type SecurityBlackboxKnowledgeRule = {
  id: string;
  categories: SecurityAttackCategory[] | ["*"];
  entrypointTypes: ["http"];
  severity: SecuritySeverity;
  title: string;
};

type SecurityKnowledgePackManifest = {
  schemaVersion: string;
  id: string;
  version: string;
  ref: string;
  rulesFile: string;
  compatibility: {
    contractVersionRange: string;
  };
};

export type SecurityBlackboxKnowledgePack = SecurityKnowledgePackManifest & {
  rules: SecurityBlackboxKnowledgeRule[];
};

type SecurityKnowledgeLoadFailure = {
  ok: false;
  reasonCode:
    | "security_knowledge_pack_reference_invalid"
    | "security_knowledge_pack_unavailable"
    | "security_knowledge_pack_incompatible";
  refs: string[];
};

const PACK_REF_PATTERN = /^([a-z0-9][a-z0-9-]*)@(\d+\.\d+\.\d+)$/;
const VERSION_PATTERN = /^(\d+)\.(\d+)\.(\d+)$/;

function parseVersion(value: string): SecurityVersion | undefined {
  const match = VERSION_PATTERN.exec(value.trim());
  if (!match) return undefined;
  return { major: Number(match[1]), minor: Number(match[2]), patch: Number(match[3]) };
}

function compareVersions(left: SecurityVersion, right: SecurityVersion): number {
  if (left.major !== right.major) return left.major - right.major;
  if (left.minor !== right.minor) return left.minor - right.minor;
  return left.patch - right.patch;
}

function satisfiesComparator(version: SecurityVersion, comparator: string): boolean {
  const match = /^(>=|<=|>|<|=|\^|~)?(\d+\.\d+\.\d+)$/.exec(comparator.trim());
  if (!match) return false;
  const targetValue = match[2];
  if (!targetValue) return false;
  const target = parseVersion(targetValue);
  if (!target) return false;
  const operator = match[1] ?? "=";
  const comparison = compareVersions(version, target);
  if (operator === ">=") return comparison >= 0;
  if (operator === "<=") return comparison <= 0;
  if (operator === ">") return comparison > 0;
  if (operator === "<") return comparison < 0;
  if (operator === "^") return version.major === target.major && comparison >= 0;
  if (operator === "~") {
    return version.major === target.major && version.minor === target.minor && comparison >= 0;
  }
  return comparison === 0;
}

function satisfiesVersionRange(versionValue: string, range: string): boolean {
  const version = parseVersion(versionValue);
  if (!version) return false;
  const comparators = range.trim() === "*" ? [] : range.trim().split(/\s+/);
  return comparators.every((comparator) => satisfiesComparator(version, comparator));
}

function defaultKnowledgePackRoot(): string {
  return path.join(__dirname, "..", "knowledge-packs");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isSafePackFileName(value: unknown): value is string {
  return typeof value === "string" && value === path.basename(value) && !value.includes("\\");
}

async function readPack(
  packRoot: string,
  directoryName: string,
): Promise<SecurityBlackboxKnowledgePack | undefined> {
  const packDirectory = path.join(packRoot, directoryName);
  try {
    const manifestValue: unknown = JSON.parse(
      await fs.readFile(path.join(packDirectory, "manifest.json"), "utf8"),
    );
    if (!isRecord(manifestValue) || !isSafePackFileName(manifestValue.rulesFile)) return undefined;
    const manifest = manifestValue as unknown as SecurityKnowledgePackManifest;
    if (
      manifest.schemaVersion !== "1.0.0" ||
      typeof manifest.id !== "string" ||
      typeof manifest.version !== "string" ||
      typeof manifest.ref !== "string" ||
      !parseVersion(manifest.version) ||
      manifest.ref !== `${manifest.id}@${manifest.version}` ||
      !isRecord(manifest.compatibility) ||
      typeof manifest.compatibility.contractVersionRange !== "string"
    ) {
      return undefined;
    }
    const rulesValue: unknown = JSON.parse(
      await fs.readFile(path.join(packDirectory, manifest.rulesFile), "utf8"),
    );
    if (!Array.isArray(rulesValue)) return undefined;
    return { ...manifest, rules: rulesValue as SecurityBlackboxKnowledgeRule[] };
  } catch {
    return undefined;
  }
}

export async function loadSecurityBlackboxKnowledgePacks(args: {
  packRefs: string[];
  contractVersion?: string;
  packRootAbs?: string;
}): Promise<{ ok: true; packs: SecurityBlackboxKnowledgePack[] } | SecurityKnowledgeLoadFailure> {
  const invalidRefs = args.packRefs.filter((ref) => !PACK_REF_PATTERN.test(ref));
  if (invalidRefs.length > 0) {
    return {
      ok: false,
      reasonCode: "security_knowledge_pack_reference_invalid",
      refs: invalidRefs,
    };
  }
  const packRoot = args.packRootAbs ?? defaultKnowledgePackRoot();
  const directories = await fs.readdir(packRoot, { withFileTypes: true }).catch(() => []);
  const available = (
    await Promise.all(
      directories
        .filter((entry) => entry.isDirectory())
        .sort((left, right) => left.name.localeCompare(right.name))
        .map((entry) => readPack(packRoot, entry.name)),
    )
  ).filter((pack): pack is SecurityBlackboxKnowledgePack => Boolean(pack));
  const unavailableRefs = args.packRefs.filter(
    (ref) => !available.some((pack) => pack.ref === ref),
  );
  if (unavailableRefs.length > 0) {
    return { ok: false, reasonCode: "security_knowledge_pack_unavailable", refs: unavailableRefs };
  }
  const contractVersion = args.contractVersion ?? "1.0.0";
  const selected = args.packRefs.map((ref) => available.find((pack) => pack.ref === ref)!);
  const incompatibleRefs = selected
    .filter(
      (pack) => !satisfiesVersionRange(contractVersion, pack.compatibility.contractVersionRange),
    )
    .map((pack) => pack.ref);
  if (incompatibleRefs.length > 0) {
    return {
      ok: false,
      reasonCode: "security_knowledge_pack_incompatible",
      refs: incompatibleRefs,
    };
  }
  return { ok: true, packs: selected };
}

export function findApplicableSecurityBlackboxRule(args: {
  packs: SecurityBlackboxKnowledgePack[];
  category: SecurityAttackCategory;
}): SecurityBlackboxKnowledgeRule | undefined {
  return args.packs
    .flatMap((pack) => pack.rules)
    .find((rule) => {
      const categories: readonly string[] = rule.categories;
      return categories[0] === "*" || categories.includes(args.category);
    });
}
