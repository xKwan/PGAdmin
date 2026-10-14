# ADR-0008: Offline dependency intake and CI platform

- **Status:** Proposed
- **Decision IDs:** D7, D8
- **Approval:** _pending_

## Context

The environment is air-gapped (Stated, F1). All tools, libraries, plugins and test components must be obtained, verified, transferred, installed and updated without internet access. CI is GitLab (Stated, F6).

## Proposed decision

**Dependency intake** (plan §7.1):
1. Change request listing new or changed packages.
2. On the connected staging side: resolve from lockfiles (Windows x64 for npm native packages), generate CycloneDX SBOMs, scan for vulnerabilities and malware.
3. SHA-256 manifest, checked by a second person.
4. Approved media transfer; manifest verified in the air gap.
5. Import into the internal artifact repository if one exists, otherwise into the file-based bundle store (`.m2` repository archive, npm cache).
6. CI job `deps:verify` must pass a clean offline install.

**Pinning:** exact versions; `package-lock.json`; Spring Boot BOM plus explicit pins; Maven Enforcer and `engines` checks. Quarterly update window plus out-of-band security updates.

**Toolchain (proposed):** Java 21 LTS, Maven 3.9.x, Node 24 LTS (build and test only). Playwright uses the installed Edge; no browser downloads.

**CI:** self-managed GitLab, Windows runner with PowerShell shell executor. No remote `include:`, no `image:`/`services:`, no external SaaS, no GitHub Actions. SonarQube gate enforcement depends on the edition (D8).

## Consequences

- `npm audit` and online CVE feeds don't work in the air gap; vulnerability status is assessed at intake.
- Dependency updates have lead time.

## Open items

Artifact repository existence (A12); transfer process; organisational JDK/Node standards; GitLab and Runner versions and offline install (A14); SonarQube edition (A9).

## Validation

Spike S4 on a clean, network-disabled Windows VM. **Not run yet.**
