export { dispatchArtifactManagementAction } from "./actions/artifact_management.action";
export { buildFailClosedArtifactResponse } from "./shared/fail_closed";
export { readProjectArtifact, writeProjectArtifact } from "./support/project_artifact_io";
export { openRunStateStore } from "./state-store/run_state_store";
export {
  acquireRegressionSuiteLease,
  persistRegressionSuiteState,
  readRegressionSuiteCheckpoint,
  releaseRegressionSuiteLease,
} from "./state-store/suite_state_store";
export {
  persistCorrelationSession,
  upsertCorrelationObservation,
} from "./state-store/correlation_state_store";
export { upsertRunStateArtifact } from "./state-store/artifact_state_store";
export { upsertWatcherRun } from "./state-store/watcher_state_store";
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
  AcquireRegressionSuiteLeaseResult,
  CorrelationObservation,
  CorrelationObservationResult,
  CorrelationPersistenceFailure,
  CorrelationSession,
  CorrelationSessionResult,
  RunStateDatabase,
  WatcherRunProjection,
  WatcherPersistenceFailure,
  WatcherPersistenceResult,
} from "./state-store/run_state_store.model";
export type { ArtifactActionContext, ArtifactActionResult } from "./actions/types";
export type { ArtifactActionRequest } from "./models/artifact_management.model";
export type ArtifactManagementFeatureModule = "artifact-management";
