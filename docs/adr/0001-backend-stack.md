# ADR-0001: Backend stack — Java, Jakarta Servlet and Waffle (no Spring)

- **Status:** **Accepted** (stack: Java + plain Jakarta Servlet + Waffle, no Spring Boot). The servlet container sub-decision below is **Proposed**.
- **Decision IDs:** D0, D7 (toolchain part), D14a
- **Approval:** 2026-10-15, stakeholder: "Use Java Servlet with waffle. No springboot." This replaces the earlier proposal (Spring Boot + `waffle-spring-security`), which was never approved. The Spring variant is kept below as history.

## Context

Waffle must remain the authentication entry point (Stated, F5). Waffle is a Java library that calls Windows SSPI through JNA. It integrates with servlet filters, Tomcat valves and Spring Security (Assumed, A1). The v1 plan used Node.js/Fastify, which cannot host Waffle.

## Options considered

1. Java + Spring Boot + Waffle's Spring Security module, as a separate service. This was the earlier proposal; the stakeholder **rejected** it.
2. **Java + plain Jakarta Servlet + Waffle's servlet filter (`waffle-jna-jakarta`), as a separate service.** The stakeholder **chose** this.
3. A Java module embedded in the existing Waffle application. Not chosen; D14a stays a separate service.
4. A Node.js backend with a token from the existing app, or a different SSPI library. **Ruled out**, because both mean replacing Waffle or adding a trust hop.

## Decision

- **Runtime:** Java 21 LTS. JDK 21.0.12 is project-installed under `tools\`. The exact pin and a possible move to 25 LTS are part of D7, which is still Proposed.
- **Web layer:** Jakarta Servlet 6.1 API only: filters, servlets and listeners. No Spring Boot, Spring MVC or Spring Security.
- **Windows authentication:** Waffle 3.7.0 `waffle-jna-jakarta`, `waffle.servlet.NegotiateSecurityFilter`, with:
  - Negotiate and NTLM only;
  - no Basic;
  - no guest login;
  - no impersonation;
  - mapped to the `REQUEST` dispatch only.
- **Frontend:** unchanged. React (JavaScript) with Vite and Vitest, served by the same service.

### Servlet container (Proposed sub-decision)

**Default:** Apache Tomcat 11.0.x **embedded** in a single executable JAR, run as a Windows service.
- Tests and production then use the same filter chain on a real container.
- One process and one artefact, matching D12.

The code uses only the Servlet API, so a WAR on a standalone Tomcat 11 Windows service remains possible if operations prefer it. Switching later changes packaging only.

## What Spring Security provided that this project now owns

Each item below is a Phase 4 deliverable with its own tests. None is satisfied by this document.

| Concern | Servlet design | Status |
|---|---|---|
| Unsupported `Authorization` schemes | A filter before Waffle hides any scheme other than Negotiate/NTLM. Waffle otherwise throws, giving HTTP 500 (finding W8). | **Tested** in spike S1-servlet |
| Fail-closed authentication | A filter after Waffle refuses any request without a `WindowsPrincipal`. Waffle skips authentication entirely when it thinks it is not on Windows. | Designed; not yet tested by inducing the bypass |
| Session fixation | Rotate the session ID (`changeSessionId()`) on first authentication, and bind the session to the user's SID | **Tested** in spike S1-servlet |
| Session cookie | `HttpOnly`, `SameSite=Strict`; `Secure` once TLS is on | `HttpOnly` and `SameSite` **Tested**; `Secure` pending TLS (P11/P12) |
| CSRF | Synchronizer token per session, required on unsafe methods (custom header), plus an `Origin` check and `SameSite=Strict` | P4 |
| Security headers | A filter sets CSP, `X-Content-Type-Options`, `frame-ancestors`, `Referrer-Policy`, `Cache-Control: no-store` on API responses, and HSTS under TLS | P3/P4 |
| Authorisation | Filters and per-endpoint checks; DataAdmin by group SID (D1) | DataAdmin-by-SID **Tested** in S1-servlet |
| Error pages | Tomcat `ErrorReportValve` with no report and no server info; JSON error envelope for the API | **Tested** for 404 in S1-servlet; 401/403/500 in P4 |
| Configuration and wiring | A properties file validated at startup; constructor wiring, no DI container | P3 |
| Backend tests | JUnit, embedded Tomcat on a random port, a raw HTTP client for SSPI, JaCoCo. No MockMvc. | P2/P3 |

## Consequences

- There are fewer dependencies and no Spring upgrade cadence to follow. In exchange, the security filters above are this project's code and need their own tests and review.
- The backend must run on Windows (SSPI). Linux containers would require replacing Waffle, which needs approval.
- Production servers need only a JRE.

## Risks and open items

- **W8 (Waffle 3.7.0 servlet filter):** an `Authorization: Basic …` or `Bearer …` header gives HTTP 500 (`RuntimeException` in `SecurityFilterProviderCollection`). Mitigated by the scheme filter, and the spike tests it on every run.
- Waffle's `excludePatterns` match the **full request URL**, not the path. The application should not need exclusions; where one is unavoidable, it must be anchored.
- Kerberos, SPN and domain groups are **Untested in target environment**. The workstation is not domain-joined.
- The existing app's Waffle version and setup are unknown (A1, C2).

## Validation

- **S1-servlet (Tested on a workgroup Windows 11 workstation, 2026-10-15):** Tomcat 11.0.24 embedded, `waffle-jna-jakarta` 3.7.0, 14 tests per run. Passed two independent randomised 5/5 loops. It covers:
  - Negotiate/NTLM logon, SID and groups;
  - DataAdmin allow/deny;
  - challenge without Basic;
  - garbage tokens, replay and split-connection rejection;
  - concurrency;
  - session cookie flags;
  - session-fixation rotation;
  - a 404 error page without server details.

  Evidence: `spikes/s1-servlet/REPORT.md`.
- **S6 against the servlet service (Tested, same workstation):** Edge 155 through Playwright logs on automatically when the host is allowlisted and withholds the logon otherwise. Evidence: `spikes/s6-e2e/REPORT.md`.

**History: Spring variant (not adopted).** S1 (partial) passed with Spring Boot 4.1.1/`waffle-spring-security7` and 3.5.16/`waffle-spring-security6`, including the rules W3 (Waffle on `REQUEST` dispatch only) and W4 (explicit session handling). Evidence: `spikes/s1-waffle/REPORT.md`. W3 does not recur in the servlet design, because Waffle is mapped to `REQUEST` only. W4 corresponds to the session-fixation rotation above.
