# 12. Conventional Commits + structured branch naming

## Status
Accepted

## Context
The first PR to this repo (#1) used a free-form commit message and an
ad hoc branch name (`docs/ai-ready-scaffolding`, chosen without a
stated rule). Without a written convention, commit style and branch
naming drift per-session and per-contributor, and the `docs/`-style
prefix that PR happened to use isn't reusable without a definition.

## Decision
Adopt [Conventional Commits](https://www.conventionalcommits.org/) for
every commit message, and a matching `<type>/<short-kebab-description>`
scheme for every branch name.

**Commit format:**
```
<type>(optional scope): <description>

[optional body]

[optional footer(s)]
```

**Branch format:**
```
<type>/<short-kebab-description>
```

**Shared type vocabulary** (used for both commits and branches):

| Type | Use for |
|---|---|
| `feat` | New functionality |
| `fix` | Bug fix |
| `docs` | Documentation only |
| `refactor` | Code change that's neither a fix nor a feature |
| `perf` | Performance improvement |
| `test` | Adding or correcting tests |
| `build` | Build system or dependency changes |
| `ci` | CI/CD pipeline changes |
| `chore` | Everything else (repo maintenance, tooling) |

Breaking changes: append `!` after the type/scope (`feat!:`) and/or add
a `BREAKING CHANGE:` footer.

## Consequences
- Commit history becomes scannable and, later, machine-parseable
  (e.g. for changelog generation) without extra tooling being required
  now.
- Branch names sort and group predictably by type in `git branch -a`
  and in GitHub's branch list.
- No enforcement mechanism (commit-msg hook, CI lint) is added by this
  decision — it's a documented convention to follow manually for now.
  Automated enforcement is a future addition if drift becomes a problem
  in practice.

## Alternatives considered
- **No fixed convention, free-form messages**: rejected — this is
  exactly the drift that prompted writing this ADR after PR #1.
- **Enforce via a commit-msg hook or CI check from day one**: deferred,
  not rejected — adds tooling overhead before there's evidence the
  manual convention isn't being followed.
