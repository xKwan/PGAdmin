# Spike S1 (servlet variant, partial): Waffle servlet filter on embedded Tomcat, no Spring

Throwaway feasibility code for the stack chosen on 2026-10-15 (ADR-0001: Java + plain Jakarta Servlet + Waffle, no Spring Boot). A minimal embedded Tomcat 11 service authenticates callers with Waffle's `NegotiateSecurityFilter` (`waffle-jna-jakarta`, Negotiate and NTLM), exposes the caller's SID and groups, and decides **DataAdmin on the server from a group SID**. It also tests the security controls that Spring Security would otherwise have provided: an `Authorization` scheme filter, fail-closed session handling with session-ID rotation, cookie flags, and error pages that show no server details.

**Partial:** this workstation is **not domain-joined**, so the handshake here is NTLM with a local account. Kerberos, domain groups, the real DataAdmin source (D1) and SPNs still need the target environment. Results are in [`REPORT.md`](REPORT.md).

| Component | Version |
|---|---|
| Apache Tomcat (embedded, `tomcat-embed-core`) | 11.0.24 |
| Waffle (`waffle-jna-jakarta`) | 3.7.0 |
| JUnit | 6.1.3 |

## Filter chain

| Order | Filter | Mapping | Purpose |
|---|---|---|---|
| 1 | `AuthorizationSchemeFilter` | `/*` | Hides `Authorization` headers other than Negotiate/NTLM, because Waffle returns HTTP 500 for them (W8) |
| 2 | Waffle `NegotiateSecurityFilter` | `/*`, `REQUEST` dispatch only | Negotiate and NTLM only; no guest login; no impersonation |
| 3 | `AuthenticatedSessionFilter` | `/*` | Fail-closed: refuses a request with no `WindowsPrincipal`; rotates the session ID on first authentication; binds the session to the SID |
| 4 | `DataAdminFilter` | `/admin/*` | 403 unless the token's group SIDs contain the configured DataAdmin SID |

Errors go through Tomcat's `ErrorReportValve`, with the report and server info turned off.

## Requirements

| Requirement | Notes |
|---|---|
| Windows 11 or Windows Server, PowerShell 5.1 | Windows SSPI is required. The tests authenticate **the account running them**. |
| Project toolchain (JDK 21, Maven 3.9) | `scripts\toolchain\Install-Toolchain.ps1` |
| Maven dependencies in `tools\m2\repository` | Fetched once on a connected machine (below). Runs are then offline (`mvn -o`). |
| Administrator rights | **Not** needed |

## Getting the dependencies (once, on a connected machine)

```powershell
. .\scripts\toolchain\Use-Toolchain.ps1
mvn -B -f spikes\s1-servlet\pom.xml dependency:go-offline
```

`junit-platform-launcher` is declared explicitly in the POM so that `go-offline` captures it (finding W6 of the Spring variant).

## Running

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File spikes\s1-servlet\Invoke-S1ServletSpike.ps1
```

- Each run is `mvn -o test` with a fresh seed. The seed randomises paths, schemes, garbage tokens, concurrency, the foreign DataAdmin SID, and test order.
- The loop continues until 5 consecutive passes. Any failure resets the count and stops the loop (`-ContinueOnFailure` overrides).
- The report goes to `results\s1s-<timestamp>.json`.

To run once directly: `mvn -o -f spikes\s1-servlet\pom.xml test -Ds1.seed=1`.

To start the service by hand: `java -cp <classpath> spike.s1servlet.SpikeServer --port=8080`. Spike S6 does this (`spikes\s6-e2e\Invoke-S6Spike.ps1`, default `-Server servlet`).

## Tests (14 per run)

The client is the same as in the Spring variant: `NegotiateClient` gets SSPI tokens for the current account through Waffle's client API and runs the handshake on a single TCP connection (`RawHttp`). No passwords are used or stored.

| Test | What it checks |
|---|---|
| `handshakeAuthenticatesTheCurrentWindowsAccount` (Negotiate, NTLM) | 200; the account name and SID match the OS; groups are present |
| `noAuthorizationHeaderGetsAChallengeWithoutBasic` | 401 offering `Negotiate` and `NTLM` only, never `Basic` |
| `garbageTokensAreRejected` | Random tokens: the first two cases are always `Basic` and `Bearer`, the rest use random schemes, some with fake NTLMSSP headers: always 401, never 200 or 5xx |
| `replayedTokenIsRejected` (Negotiate, NTLM) | The final token of a completed handshake doesn't authenticate on a new connection |
| `handshakeSplitAcrossConnectionsFails` (Negotiate, NTLM) | The second leg on a different connection fails |
| `concurrentHandshakes` | Parallel handshakes all succeed for the right account |
| `dataAdminEndpointAllowedForConfiguredGroup` | DataAdmin SID = a group the user is in: `/admin/ping` gives 200 |
| `DataAdminDeniedServletTest` | DataAdmin SID = a random foreign SID: the user authenticates, `/admin/ping` gives **403** |
| `sessionCookieAfterHandshake` | The session cookie is `HttpOnly` and `SameSite=Strict`; the session alone then authenticates later requests |
| `plantedSessionIsRotatedOnAuthentication` | A pre-authentication session ID is replaced on logon; the old ID gives 401 |
| `errorPagesDoNotLeakServerDetails` | A 404 body contains no server version or stack trace, and there is no `Server` header |
