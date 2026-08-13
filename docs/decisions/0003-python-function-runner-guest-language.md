# 3. Python as the function runner's guest language

## Status
Accepted

## Context
The function runner (stretch goal) needs to execute user-uploaded
functions in some guest language, separate from the Go host process that
manages invocation, sandboxing, and triggers.

## Decision
Support exactly one guest runtime: Python.

## Consequences
- Gives a realistic, legitimate reason to touch Python in this project
  without making it the primary implementation language — Go
  ([0002](0002-go-backend-language.md)) still owns the host/runner
  logic.
- Mirrors real Lambda-style platforms, which are polyglot at the guest
  layer while the control plane is written in one systems language.
- Multi-runtime support (Node, Java, etc.) is explicitly out of scope —
  see `future-work.md`.

## Alternatives considered
- **JavaScript/Node as the guest runtime**: rejected — no added signal
  over Python for this project's goals, and Python is the more common
  "glue/scripting" counterpart to a Go-based platform in practice.
- **Multiple guest runtimes from day one**: rejected as scope creep;
  the project already has enough surface area in S3 + IAM + platform
  layer without also building a runtime-plugin system.
