# Web admin console

**Status:** not yet built. No dedicated build-order slot in
`architecture.md` §11 yet — expect this to firm up once S3 and IAM have
real APIs to point a UI at.

## Scope (as currently understood)

A small admin console for managing buckets, policies, and viewing
invocation logs — not a consumer-facing product. See
[`../decisions/0004-react-frontend.md`](../decisions/0004-react-frontend.md)
for why React was chosen and why framework choice isn't the signal
this project is optimizing for.

## Dependencies

- Reads from the S3 and IAM APIs (see [`s3.md`](s3.md),
  [`iam.md`](iam.md)); no direct database access.

## Open questions

- Exact page/feature list isn't designed yet — revisit this file once
  S3 and IAM APIs exist and there's something concrete to build a UI
  against.
