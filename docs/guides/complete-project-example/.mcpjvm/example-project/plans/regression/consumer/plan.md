# Consumer event indexing

## Purpose

Read the producer event, wait for eventual indexing, and verify the downstream PostgreSQL row.

## Targets

`com.example.events.EventQueryController#getImport` under `src/main/java`.

## Prerequisites

- `apiBaseUrl` is a user-provided non-secret base URL.
- `auth.bearer` is re-resolved from the project-owned runtime context on resume.
- `eventId` is promoted explicitly by the earlier producer plan.

## Steps

1. Executes `GET ${apiBaseUrl}/imports/${eventId}`.
2. Uses the promoted `eventId` and resolved bearer token.
3. WaitsFor the bounded Watcher to observe `response.bodyJson.state == "ready"`.
4. Verifies the PostgreSQL row with a bound `eventId` parameter.

## Expected Outcomes

- Returns HTTP 200 from the consumer endpoint.
- Emits Watcher attempts with a bounded deadline.
- Produces a PostgreSQL verification summary.
- Matches `indexed_count >= 1`.
- Passes without rerunning the producer trigger on resume.
