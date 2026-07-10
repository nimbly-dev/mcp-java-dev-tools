export { artifactManagementDomain } from "./domain";
export { buildFailClosedArtifactResponse } from "./shared/fail_closed";
export { readProjectArtifact, writeProjectArtifact } from "./support/project_artifact_io";
export type { ArtifactActionContext, ArtifactActionResult } from "./actions/types";
export type ArtifactManagementFeatureModule = "artifact-management";
