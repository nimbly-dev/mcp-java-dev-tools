export { dispatchArtifactManagementAction } from "./actions/artifact_management.action";
export { buildFailClosedArtifactResponse } from "./shared/fail_closed";
export { readProjectArtifact, writeProjectArtifact } from "./support/project_artifact_io";
export { openRunStateStore, persistRegressionSuiteState, readRegressionSuiteCheckpoint, upsertRunStateArtifact } from "./state-store/run_state_store";
export type {
  OpenRunStateStore,
  RunStateArtifactLink,
  RunStateStoreFailure,
  RunStateStoreFailureCode,
  RunStateStoreOpenResult,
  PersistRegressionSuiteStateResult,
  RegressionPlanRunProjection,
  RegressionSuiteCheckpoint,
  RunStateCheckpointFailure,
  PersistedRegressionSuiteCheckpoint,
} from "./state-store/run_state_store";
export type { ArtifactActionContext, ArtifactActionResult } from "./actions/types";
export type { ArtifactActionRequest } from "./models/artifact_management.model";
export type ArtifactManagementFeatureModule = "artifact-management";
