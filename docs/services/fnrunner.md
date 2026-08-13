# Function runner

**Status:** not yet built. Stretch goal — build last, per
`architecture.md` §11, after S3, IAM, and the platform layer
(k3s/Helm/ArgoCD/CI/monitoring) are in place.

## Scope

- Upload a function, trigger on S3 object-created events
- Isolated per-invocation execution (cold starts, sandboxing, timeouts)
- Per-invocation log capture, viewable via API

## Guest runtime

Python only — see
[`../decisions/0003-python-function-runner-guest-language.md`](../decisions/0003-python-function-runner-guest-language.md).
The host/runner process itself is Go, matching the other services (see
[`../decisions/0002-go-backend-language.md`](../decisions/0002-go-backend-language.md)).

## Dependencies

- Triggered by the S3 service on object-created events (see
  [`s3.md`](s3.md)).

## Out of scope

See `../future-work.md` — multiple guest runtimes, concurrency
scaling/provisioned concurrency, and VPC-style networking for
functions are explicitly not part of this service.
