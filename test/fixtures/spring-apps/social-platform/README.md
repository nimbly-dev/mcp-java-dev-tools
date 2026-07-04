# Social Platform Fixture

This fixture is a real multi-module Spring workspace used for upcoming integration tests against:
- Java agent instrumentation
- Spring request mapping synthesis
- MCP end-to-end tool execution

## Modules

- `shared-domain`: shared DTOs, enums, and Jackson view contracts
- `shared-security-annotations`: realistic custom annotations defined outside the service modules
- `auth-service/auth-api`: public and protected auth controller contracts
- `auth-service/auth-app`: runnable auth service
- `event-service/event-api`: authenticated event trigger and event-status contracts
- `event-service/event-app`: runnable async event workflow service
- `user-service/user-api`: public and protected user controller contracts
- `user-service/user-app`: runnable user service
- `post-service/post-api`: public and protected post/feed controller contracts
- `post-service/post-app`: runnable post service

## Custom Annotation Coverage

- `@ApiRunAsHeaders`: non-mapping annotation that must be ignored by route synthesis
- `@OwnerOrAdmin`: meta-security annotation around `@PreAuthorize` for protected post ownership flows

## Endpoint Mapping

### Auth Service
- `POST /api/v1/auth/register` public
- `POST /api/v1/auth/login` public
- `GET /api/v1/auth/me` bearer protected

### Event Service
- `POST /api/v1/events/trigger` bearer protected and publishes an async in-process event
- `GET /api/v1/events/{eventId}` bearer protected and returns accepted/processed status
- `event-producer-app`: authenticated trigger service that forwards event work downstream
- `event-consumer-app`: downstream internal consumer that dispatches to an async listener

### User Service
- `GET /api/v1/users/{username}` public
- `PUT /api/v1/users/{username}` owner-or-admin protected
- `POST /api/v1/users/{username}/follow` bearer protected
- `DELETE /api/v1/users/{username}/follow` bearer protected

### Post Service
- `GET /api/v1/posts` public
- `GET /api/v1/posts/{postId}` public
- `POST /api/v1/posts` bearer protected and stacked with `@ApiRunAsHeaders`
- `PUT /api/v1/posts/{postId}` owner-or-admin protected and stacked with `@ApiRunAsHeaders`
- `DELETE /api/v1/posts/{postId}` owner-or-admin protected
- `GET /api/v1/feed` bearer protected

## Fixture Tokens

- `alice-token` => `alice` with role `USER`
- `bob-token` => `bob` with role `USER`
- `admin-token` => `admin` with roles `USER`, `ADMIN`

Any token ending in `-token` is also accepted as a `USER` token by the fixture security filter.
