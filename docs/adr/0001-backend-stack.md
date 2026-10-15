# ADR-0001: Backend stack — Java, Spring Boot and Waffle

- **Status:** Proposed
- **Decision IDs:** D0, D7 (toolchain part), D14a
- **Approval:** _pending_

## Context

Waffle must remain the authentication entry point (Stated, F5). Waffle is a Java library that calls Windows SSPI through JNA and integrates with servlet filters and Spring Security (Assumed, A1). The v1 plan used Node.js/Fastify, which cannot host Waffle.

## Options

1. **Java 21 + Spring Boot + Waffle in a separate service** (recommended).
2. Java service embedded as a module in the existing Waffle application.
3. Node.js backend trusting a token issued by the existing Java app. Adds a trust hop and new code in the existing app.
4. Node.js with a different SSPI library. Replaces Waffle; **ruled out** without approval.

## Proposed decision

Option 1. Java 21 LTS (or 25 LTS if that is the organisational standard), Spring Boot 3.5.x or 4.0.x, Waffle 3.x with the Spring Security module matching the Spring Security major. The frontend stays React (JavaScript) with Vite and Vitest.

## Consequences

- Backend testing uses JUnit 5, Spring Boot Test and JaCoCo; frontend testing stays Vitest and RTL.
- The backend must run on Windows (SSPI). Linux containers would require replacing Waffle (approval needed).
- Production servers need only a JRE.

## Risks and open items

- **Waffle and Spring Security 7:** A2 is partly resolved.
  - Waffle 3.7.0 ships `waffle-spring-security7`, built against Spring Security 7.1.1.
  - Spike S1 (partial) passed with Spring Boot 4.1.1 and with 3.5.16, using the same code.
  - Kerberos and domain groups are untested.
- **Two integration rules from S1:**
  - Waffle must run on the `REQUEST` dispatch only, otherwise a 403 becomes a 401 (W3).
  - The security context must be saved to the session explicitly (W4).
- The existing app's Waffle version and framework are unknown (A1). Waffle 1.x/2.x use `javax`; 3.x uses `jakarta`.
- Separate service vs embedded module (D14a) is unresolved.

## Validation

Spike S1: a minimal app on a domain-joined, target-like Windows server authenticates a domain user via Negotiate (Kerberos) in Edge, reads SID and groups, and resolves DataAdmin. **Not run yet in a domain.** The planning workstation is not domain-joined (see `docs/environment-inventory.md` §1).

**S1 partial (Tested on a workgroup Windows 11 workstation, 2026-10-15):**
- NTLM and Negotiate (NTLM fallback) authenticate the local account;
- SID and groups are read;
- DataAdmin is decided by group SID;
- rejection, replay and split-connection cases are handled, on both Boot 4.1.1/Security 7 and Boot 3.5.16/Security 6.

Evidence: `spikes/s1-waffle/REPORT.md`. Kerberos, SPN, domain groups and Edge remain **Untested in target environment**.
