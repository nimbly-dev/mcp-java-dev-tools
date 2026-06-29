# Post Lifecycle Regression Plan (Runtime Pinned)

## Purpose

Validate create/update lifecycle with strict pinned runtime verification targets.

## Targets

- `PostController#createPost`
- `PostController#updatePost`

## Prerequisites

- `auth.bearer` (required, secret)
- `tenantId` (required)

## Steps

1. Executes `create_post` using `POST /api/v1/posts`.
2. Captures `response.bodyJson.id` as `postId`.
3. Uses `postId` in `PUT /api/v1/posts/${postId}`.
4. Executes `update_post`.
5. Verifies pinned strict probe keys for both steps.

## Expected Outcomes

1. Returns `201` for `create_post`.
2. Returns `200` for `update_post`.
3. Passes probe verification for pinned keys.
4. Passes overall plan result.

## Notes

- Canonical JSON extraction path: `response.bodyJson.*`
- Use `response.body` only when the contract intentionally needs raw response text.

