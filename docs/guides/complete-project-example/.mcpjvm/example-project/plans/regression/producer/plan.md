# Producer event creation

## Purpose

Create an event and promote its non-secret identifier for the later consumer plan.

## Targets

`com.example.events.EventController#create` under `src/main/java`.

## Prerequisites

- `apiBaseUrl` is a user-provided non-secret base URL.
- `auth.bearer` is resolved from the project-owned runtime context and is never persisted.

## Steps

1. Executes `POST ${apiBaseUrl}/events` with the resolved bearer token.
2. Captures `response.bodyJson.id` as `eventId`.
3. Sets `eventId` to suite scope with `secret: false` after the producer passes.
4. Verifies the accepted response and correlation evidence.

## Expected Outcomes

- Returns HTTP 202.
- Emits a bounded correlation observation.
- Produces a suite-scoped `eventId` for the consumer.
- Matches the required Strict Line expectation.
- Passes without persisting the bearer token.
