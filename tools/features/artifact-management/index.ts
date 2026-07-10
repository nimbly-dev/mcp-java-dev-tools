export { artifactManagementDomain } from "./actions/artifact_management.action";
export { buildFailClosedArtifactResponse } from "./shared/fail_closed";
export { readProjectArtifact, writeProjectArtifact } from "./support/project_artifact_io";
export type { ArtifactActionContext, ArtifactActionResult } from "./actions/types";
export type { ArtifactActionRequest } from "./models/artifact_management.model";
export type ArtifactManagementFeatureModule = "artifact-management";
