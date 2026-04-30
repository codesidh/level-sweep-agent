# Contributing to LevelSweepAgent

Working conventions for branches, PRs, and issue tracking. Read once; follow on every change.

## Branch naming

```
main                          ← always green, deployable, tagged for releases
feat/p<phase>-<short-slug>    ← features (most work)
fix/<short-slug>              ← bug fixes
chore/<short-slug>            ← tooling / deps / ops
docs/<short-slug>             ← documentation only
refactor/<short-slug>         ← code change without behavior change
test/<short-slug>             ← test additions / improvements
```

Examples: `feat/p1-alpaca-marketdata`, `fix/spotless-trailing-comma`, `chore/ci-trivy-bump`.

Always branch off `main` after `git pull --rebase`.

## Commit messages

Conventional Commits format:

```
<type>(<scope>): <subject>

<body>

<footer>
```

- **type**: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`
- **scope** (optional): area shorthand — `data`, `decision`, `execution`, `ai`, `journal`, `frontend`, `infra`, `ci`, `phase-N`
- **subject**: imperative, lowercase, no trailing period
- Reference ADR if architectural: `feat(saga): add sentinel veto step (ADR-0002)`
- Reference issue if bugfix: `fix(execution): retry exit on alpaca 502 (#42)`

**No `Co-Authored-By: Claude` trailer.** All commits authored solely by `codesidh` (or future contributors when Phase B opens). Configure git locally:

```bash
git config user.name codesidh
git config user.email codesidh@gmail.com
```

## Pull requests

1. Create a branch and push at least one commit.
2. Open a PR against `main`. The template at `.github/pull_request_template.md` populates automatically.
3. Fill in the **trading discipline checklist** (replay parity, idempotency, tenant scoping, audit trail, no real money, no credential logs).
4. If touching AI code, fill in the **AI discipline checklist** (prompt versioning, cost cap, tool-use protocol, fail-open behavior).
5. If introducing Phase B functionality, fill in the **Phase B check** (feature flag default OFF, documented).
6. Wait for CI to pass:
   - `pr` workflow: lint + Spotless apply + build + tests + Terraform fmt/validate
   - `iac` workflow: only fires on `infra/**` changes
7. **Phase A**: self-merge once CI is green. **Phase B**: required reviewer enforced.
8. Squash-merge to keep `main` linear. Branch auto-deletes on merge.

Branch protection on `main` is a **TODO** (requires GitHub Pro on private repos, or repository rulesets); for now the conventions are honor-system. See issue tracker.

## Issues + milestones

- Every PR closes an issue via `Closes #N` in the description (auto-link).
- One milestone per build phase (`Phase 0 — Scaffolding & Ops` … `Phase 11 — Phase B launch`).
- Phase 1 issues are detailed; Phase 2–11 are tracking parents that fan out into detail tickets when that phase begins.

### Labels

| Group | Labels | When to use |
|---|---|---|
| Phase | `phase-0` … `phase-11` | One per issue, matches milestone |
| Area | `area:data`, `area:decision`, `area:execution`, `area:ai`, `area:journal`, `area:frontend`, `area:infra`, `area:ci`, `area:docs` | One or more |
| Type | `type:feat`, `type:fix`, `type:chore`, `type:docs`, `type:refactor`, `type:test` | Exactly one |
| Priority | `p0:blocker`, `p1:high`, `p2:normal`, `p3:low` | Exactly one |
| Special | `phase-b-only`, `external-blocker` | When applicable |

## Pre-commit

Install once per clone:

```bash
pip install pre-commit
pre-commit install
```

The hooks in `.pre-commit-config.yaml` run on every commit:
- Whitespace / line-ending hygiene
- `check-yaml`, `check-json`, `check-added-large-files` (≤512 KB), `detect-private-key`
- `gitleaks` (secret scanning)
- `terraform_fmt` + `terraform_validate`
- Spotless (Java + Kotlin formatting)

Skip in emergencies (discouraged): `git commit --no-verify`.

## Local CI dry-run

Mirror the CI gates locally before pushing:

```bash
./gradlew spotlessApply             # autofix formatting
git diff -- ':!gradlew' ':!gradlew.bat' ':!gradle/wrapper/'   # see what changed
./gradlew build                     # compile + unit tests
./gradlew integrationTest           # docker-compose-backed tests
./gradlew replayTest                # required for any decision-engine change
```

If `git diff` shows changes after `spotlessApply`, commit them before pushing.

## Authoritative docs

| Document | Source of truth for |
|---|---|
| [`requirements.md`](requirements.md) | Strategy specification — what the agent does |
| [`architecture-spec.md`](architecture-spec.md) | System architecture — how it's built |
| [`adr/`](adr/) | Architectural decisions in force |
| [`CLAUDE.md`](CLAUDE.md) | Project guardrails for AI-assisted development |
| [`docs/feature-flags.md`](docs/feature-flags.md) | Phase B feature flag inventory |
| [`docs/local-dev.md`](docs/local-dev.md) | Run the stack on a laptop |

## When to write an ADR

- Choice between viable alternatives where the rejected options had meaningful tradeoffs
- A constraint future engineers might re-litigate without context
- A reversal of a prior decision

Trivial choices (variable names, file layout) don't need ADRs.

## CI debugging tips

When CI fails, prefer **rule-level fixes** over **single-file fixes**:
- Spotless violation → run `./gradlew spotlessApply` over the whole tree
- Linter rule fits poorly → adjust globally via `.editorconfig`
- Dependency missing → verify on registry (`curl Maven Central`, `npm view`, `gh api`)

See `.claude/skills/gradle-build-conventions/SKILL.md` for the full pattern catalogue.
