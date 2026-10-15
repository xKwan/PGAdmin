# Spike S1 (partial): Waffle Windows authentication with Spring Security

Throwaway feasibility code for plan §2.3 and Phase 1 spike S1. A minimal Spring Boot service authenticates callers with **Waffle** (Windows SSPI: Negotiate and NTLM), exposes the caller's SID and groups, and decides **DataAdmin on the server from a group SID**.

**Partial:** this workstation is **not domain-joined**, so the handshake here is NTLM with a local account. Kerberos, domain groups, the real DataAdmin source (D1), SPNs and Edge on a domain-joined host still need the target environment. Results are in [`REPORT.md`](REPORT.md).

The same code is tested against two Spring lines:

| Maven profile | Spring Boot | Spring Security | Waffle module |
|---|---|---|---|
| `boot4` (default) | 4.1.1 | 7.x | `waffle-spring-security7` 3.7.0 |
| `boot3` | 3.5.16 | 6.x | `waffle-spring-security6` 3.7.0 |

## Requirements

| Requirement | Notes |
|---|---|
| Windows 11 or Windows Server, PowerShell 5.1 | Windows SSPI is required. The tests authenticate **the account running them**. |
| Project toolchain (JDK 21, Maven 3.9) | `scripts\toolchain\Install-Toolchain.ps1` |
| Maven dependencies in `tools\m2\repository` | Fetched once on a connected machine for **both** profiles (below). Runs are then offline (`mvn -o`). |
| Administrator rights | **Not** needed |

## Getting the dependencies (once, on a connected machine)

```powershell
. .\scripts\toolchain\Use-Toolchain.ps1
mvn -B -f spikes\s1-waffle\pom.xml -Pboot4 dependency:go-offline
mvn -B -f spikes\s1-waffle\pom.xml -Pboot3 dependency:go-offline
```

`junit-platform-launcher` is declared explicitly in the POM. Otherwise Surefire downloads it at test time, and `go-offline` misses it (finding W6).

## Running

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File spikes\s1-waffle\Invoke-S1Spike.ps1
```

- Each run tests both profiles, in random order, with a fresh seed. A run passes only if both pass.
- The loop continues until 5 consecutive passes.
- Any failure resets the count and stops the loop (`-ContinueOnFailure` overrides).
- The report goes to `results\s1-<timestamp>.json`.

To run one line directly: `mvn -o -f spikes\s1-waffle\pom.xml -Pboot3 test -Ds1.seed=1`.

## How it authenticates in tests

`NegotiateClient` acts like a browser doing integrated authentication:
1. It obtains SSPI tokens for the current Windows account through Waffle's client API (`WindowsSecurityContextImpl.getCurrent`).
2. It runs the multi-leg handshake on a single TCP connection (`RawHttp`), because NTLM is connection-bound.

No passwords are used or stored.

| Test | What it checks |
|---|---|
| `handshakeAuthenticatesTheCurrentWindowsAccount` | Negotiate and NTLM: 200; the account name and SID match the OS; groups include BUILTIN\Users and Everyone |
| `noAuthorizationHeaderGetsAChallengeWithoutBasic` | 401 offering `Negotiate` and `NTLM` only, never `Basic`; no identity data |
| `garbageTokensAreRejected` | 10–25 random tokens per run (random schemes and lengths, some with a fake NTLM header): never 200, never 5xx |
| `replayedTokenIsRejected` | The final token of a completed handshake doesn't authenticate on a new connection |
| `handshakeSplitAcrossConnectionsFails` | The second leg on a different connection fails |
| `concurrentHandshakes` | 2–8 parallel handshakes all succeed for the right account |
| `dataAdminEndpointAllowedForConfiguredGroup` | DataAdmin SID = BUILTIN\Users: `/admin/ping` gives 200 |
| `DataAdminDeniedTest` | DataAdmin SID = a random foreign SID: the user authenticates, `/admin/ping` gives **403**, and spoofed role headers change nothing |
| `sessionCookieAfterHandshake` | Records whether a session is created after authentication |
