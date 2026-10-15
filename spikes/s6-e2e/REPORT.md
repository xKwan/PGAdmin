# Spike S6 (partial) and S4 (npm part): Report

**Result: PARTIAL PASS on a workgroup Windows 11 workstation.**
- Playwright 1.63.0 drives the **installed Edge 155** without downloading any browser.
- Edge performs **Windows integrated authentication** against the S1 Waffle service when the host is allowlisted, and withholds the Windows logon when it isn't.
- The JavaScript dependencies reinstall **with the npm registry unreachable**, from the project-local cache, in every run.

**S6 stays open for:**
- the GitLab Windows runner (service account, desktop session, Edge there);
- Kerberos and domain GPOs.

**S4 stays open for:** a clean, network-disabled VM.

## Environment

| Item | Value |
|---|---|
| Machine | Windows 11 Pro 10.0.26200, **not domain-joined**, local account |
| Browser | Microsoft Edge 155.0.4283.45 (system install), Playwright channel `msedge`, headless |
| Node.js / npm | 24.20.0 / 11.19.0 (pre-existing) |
| Playwright | `@playwright/test` 1.63.0, pinned with SHA-512 integrity in `package-lock.json`. 1.64.x was available but under two weeks old. |
| Server under test | Default since 2026-10-15: S1 servlet service (`spikes\s1-servlet`), embedded Tomcat 11.0.24 + `waffle-jna-jakarta` 3.7.0, no Spring. Before that: S1 Spring service, Spring Boot 4.1.1 + `waffle-spring-security7` 3.7.0 (`-Server spring`). Both are built offline. |
| npm cache | `tools\npm-cache` (`cache.npm` in `configuration.properties`) |

## Loop history (2026-10-15)

| Run | Result | Cause | Fix |
|---|---|---|---|
| trial (1 run) | FAIL: 4/6 | The two "no automatic logon" tests expected a 401 page. Edge actually asks for explicit credentials, which headless Playwright reports as `net::ERR_INVALID_AUTH_CREDENTIALS` after about 30 s. | Tests now assert that error. It is the safe behaviour: no Windows logon was sent. |
| `s6-20261015-093559.json` | **PASS 5/5** (6 tests per run, npm offline reinstall 3.9–4.5 s) | Final code | Master seed `1142775326` |
| `s6-20261015-094255.json` | **PASS 5/5** (6 tests per run, npm offline reinstall 3.3–4.1 s) | Independent confirmation, same code | Master seed `936320451` |
| `-Prepare` path (scratch, 1 run) | PASS | Checked that the documented first-time command works | — |
| `s6-20261015-201007.json` | **PASS 5/5** (6 tests per run) | Same tests against the **servlet** service (`-Server servlet`, now the default), after the stack decision | Master seed `1592015109` |

## Findings

| # | Finding | Status | Plan impact |
|---|---|---|---|
| E1 | Playwright's `msedge` channel launches the installed Edge with `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`. No browser binaries are downloaded and `tools\ms-playwright` stays empty. | **Tested** | No browser artefacts to bring into the air gap. C8 is tested for Edge 155. |
| E2 | With `--auth-server-allowlist=localhost`, Edge completes the Negotiate/NTLM handshake automatically, against both the Spring and the servlet service. The service sees the test account's SID and groups, `/admin/ping` gives 200, and 2–4 parallel pages all authenticate. | **Tested** (NTLM) | Real-browser login works with the S1 configuration, including the W3 fix |
| E3 | When the host doesn't match the allowlist, Edge **doesn't send the Windows logon** and asks for explicit credentials. Matching is per **host name**: an allowlist entry `localhost` doesn't cover `127.0.0.1`. | **Tested** | Deployment: users must open the app under the exact host name in the GPO `AuthServerAllowlist` (or intranet zone). Otherwise they get a credentials prompt rather than silent logon. Put the canonical URL in the user guide (P12). |
| E4 | Without any allowlist switch, this machine's default policy also logged on automatically to `localhost` (Windows treats it as the Local intranet zone) | **Observed** (not asserted) | Depends on machine policy. Domain desktops must be checked (inventory §3, browser GPO). |
| E5 | `npm ci --offline` with the registry set to an unreachable address reinstalled the dependencies in about 4 s in **every run**, from `tools\npm-cache`. The lock file's SHA-512 integrity hashes are checked against the cache. | **Tested** | Confirms the npm side of the offline procedure (plan §7.1) on this machine. A clean VM is still needed for S4 proper. |
| E6 | Two tests wait about 30 s for Edge to give up asking for credentials | **Observed** | For CI, keep these "no-logon" checks in a small suite, not on every pipeline |

## Still open

| Item | Needed |
|---|---|
| GitLab Windows runner: Edge under the runner's account, headless, desktop/session limits | Runner access (A8) |
| Kerberos through Edge, domain `AuthServerAllowlist` / intranet zone | Domain-joined desktop or runner |
| S4 proper: JDK, Maven, Node and bundles on a **clean, network-disabled VM** | A VM |
