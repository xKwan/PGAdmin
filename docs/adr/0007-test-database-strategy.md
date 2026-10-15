# ADR-0007: Integration-test database strategy without Docker

- **Status:** **Accepted**
- **Decision IDs:** D6
- **Approval:** 2026-10-15, stakeholder: "Approve all" of the listed defaults (explicit; recorded in `docs/decision-log.md`).

## Context

Integration tests must run against real PostgreSQL in the air-gapped Windows environment. Docker and Testcontainers can't be assumed (Stated, F7).

## Proposed decision

- **Default:** a throwaway cluster per test run, created with the installed PostgreSQL `initdb`/`pg_ctl` (same major version as production): temp data directory, random port, `localhost` only, generated superuser password, seeded test roles and databases, audit DB migrated with the production migrations. Stopped and deleted afterwards, including after failures.
- **Fallback:** a dedicated test PostgreSQL service on the runner, with per-pipeline databases and prefixed roles, dropped after each run and swept nightly.
- **Safety guard:** tests connect only to clusters the harness created (marker file + port + per-run token). Production settings can't load under the test profile.
- **Future:** the harness sits behind a `PgTestCluster` interface; a Testcontainers implementation can be added later without changing tests.

## Consequences

- Runners need PostgreSQL binaries of the production major version and permission to run `initdb`/`pg_ctl`.
- The planning workstation has PostgreSQL 18.6 binaries (Verified); usable for tests only if the targets are also 18 (C12).

## Validation

Spike S5 on the actual GitLab Windows runner, including cleanup after a forced failure. **Not run yet.**
