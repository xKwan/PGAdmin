# Spike S6 (partial): Edge end-to-end tests with Windows integrated authentication

Throwaway feasibility code for plan Phase 1 spikes **S6** and, for the JavaScript part, **S4**. It answers three questions:
1. Can Playwright drive the **installed Microsoft Edge**, with no browser download?
2. Does Edge perform **Windows integrated authentication** (Negotiate/NTLM through SSPI) against the Waffle service from spike S1?
3. Does the browser's allowlist policy control that?

For S4, it also checks that the JavaScript dependencies install **with no network access** from a project-local npm cache.

**Partial:** workgroup machine, local account, NTLM. Kerberos, domain GPOs (`AuthServerAllowlist`, intranet zone) and the GitLab runner still need the target environment. Results are in [`REPORT.md`](REPORT.md).

## Requirements

| Requirement | Notes |
|---|---|
| Windows 11 or Windows Server, PowerShell 5.1 | |
| Microsoft Edge (installed) | Used through Playwright's `msedge` channel. **No browser is downloaded** (`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`). |
| Node.js 24 (pre-existing on this machine) | Listed in `software-list.md` |
| Project toolchain (JDK 21, Maven 3.9) and the S1 Maven dependencies | To build and run the S1 Waffle service offline |
| npm cache in `tools\npm-cache` | Filled once on a connected machine (`-Prepare`). Recorded in `configuration.properties` as `cache.npm`. |

## Preparing (once, on a connected machine)

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File spikes\s6-e2e\Invoke-S6Spike.ps1 -Prepare -MaxRuns 1 -RequiredConsecutivePasses 1
```

`-Prepare` runs `npm ci` with `npm_config_cache=tools\npm-cache` (set by `Use-Toolchain.ps1`). The exact package versions and their SHA-512 integrity hashes are pinned in `package-lock.json`: `@playwright/test` 1.63.0, `playwright` 1.63.0 and `playwright-core` 1.63.0. For an air-gapped machine, copy `tools\npm-cache\` along with the rest of `tools\`.

## Running

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File spikes\s6-e2e\Invoke-S6Spike.ps1
```

Each run:
1. deletes `node_modules` and reinstalls it with `npm ci --offline`, with the registry set to an unreachable address. Any network access would fail the install.
2. starts the S1 service on a random free port: by default the servlet service (`spikes\s1-servlet`, embedded Tomcat 11 + Waffle servlet filter); `-Server spring` uses the superseded Spring Boot 4.1 service (`spikes\s1-waffle`).
3. runs the Playwright tests in headless Edge.
4. stops the service and checks the port is released.

The loop continues until 5 consecutive passes. Any failure resets the count and stops the loop (`-ContinueOnFailure` overrides). The report goes to `results\s6-<timestamp>.json`, with every recorded observation. A run takes about 80 seconds, because two tests wait for Edge to give up asking for credentials.

## Tests

| Test | What it checks |
|---|---|
| allowlisted host | With `--auth-server-allowlist=localhost` (the command-line form of the `AuthServerAllowlist` policy), Edge logs on automatically. The service sees the test account's SID and its BUILTIN\Users membership. |
| host outside the allowlist | With a non-matching allowlist, Edge does **not** send the Windows logon. It asks for explicit credentials (`ERR_INVALID_AUTH_CREDENTIALS` headless). |
| allowlist is per host name | `127.0.0.1` isn't covered by an allowlist entry `localhost` |
| DataAdmin endpoint | `/admin/ping` gives 200 through the browser (DataAdmin = BUILTIN\Users in S1's default configuration) |
| several pages at once | 2–4 pages authenticate concurrently in one browser context |
| observe: default policy | Without the switch, records what this machine's policy does (not asserted) |
