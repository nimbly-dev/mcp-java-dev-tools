# Post Lifecycle Regression Plan (Runtime Auto)

## Purpose

Validate create/delete post lifecycle with runtime verification enabled and strict probe key auto-resolution.

## Targets

- `PostController#createPost`
- `PostController#deletePost`

## Prerequisites

- `auth.bearer` (required, secret)
- `tenantId` (required)

## Steps

1. Executes `create_post` using `POST /api/v1/posts`.
2. Captures `response.bodyJson.id` as `postId`.
3. Uses `postId` in `DELETE /api/v1/posts/${postId}`.
4. Executes `delete_post`.

## Expected Outcomes

1. Returns `201` for `create_post`.
2. Returns `204` for `delete_post`.
3. Passes overall plan result.

## Notes

- Canonical JSON extraction path: `response.bodyJson.*`
- Use `response.body` only when the contract intentionally needs raw response text.

