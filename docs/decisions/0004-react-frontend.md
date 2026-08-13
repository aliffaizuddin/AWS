# 4. React for the admin console

## Status
Accepted

## Context
The project needs a small web UI for managing buckets, policies, and
invocation logs. This is a genuinely small admin surface, not a
consumer-facing product.

## Decision
Build the admin console in React.

## Consequences
- Lighter-weight fit than Angular's more enterprise-scale opinionated
  structure for a console this size.
- Framework choice is explicitly not the signal this project is trying
  to send — backend and infra depth are — so the simplest reasonable
  choice wins.

## Alternatives considered
- **Angular**: rejected — more structure/ceremony than a small admin
  console needs; would spend effort on frontend architecture that
  doesn't serve the project's actual goals.
- **No UI, API + CLI only**: considered, but a minimal web console adds
  a demoable surface for interviews at low relative cost.
