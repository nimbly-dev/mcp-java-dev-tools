import path from "node:path";
import { promises as fs } from "node:fs";

export async function fileExists(abs: string): Promise<boolean> {
  try {
    return (await fs.stat(abs)).isFile();
  } catch {
    return false;
  }
}

async function dirExists(abs: string): Promise<boolean> {
  try {
    return (await fs.stat(abs)).isDirectory();
  } catch {
    return false;
  }
}

export async function inspectProjectRoot(projectRootAbs: string): Promise<{
  buildMarkers: string[];
  hasBuildMarker: boolean;
  javaSourceRoots: string[];
  hasJavaSourceRoot: boolean;
}> {
  const buildMarkers: string[] = [];
  if (await fileExists(path.join(projectRootAbs, "pom.xml"))) buildMarkers.push("pom.xml");
  if (await fileExists(path.join(projectRootAbs, "build.gradle")))
    buildMarkers.push("build.gradle");
  if (await fileExists(path.join(projectRootAbs, "build.gradle.kts")))
    buildMarkers.push("build.gradle.kts");
  const javaSourceRoots: string[] = [];
  const sourceRootAbs = path.join(projectRootAbs, "src", "main", "java");
  if (await dirExists(sourceRootAbs)) javaSourceRoots.push(sourceRootAbs);
  return {
    buildMarkers,
    hasBuildMarker: buildMarkers.length > 0,
    javaSourceRoots,
    hasJavaSourceRoot: javaSourceRoots.length > 0,
  };
}
