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

- **Whether Waffle supports Spring Security 7 (Spring Boot 4.x) is unknown** (A2). Fallback: Spring Boot 3.5.x.
- The existing app's Waffle version and framework are unknown (A1). Waffle 1.x/2.x use `javax`; 3.x uses `jakarta`.
- Separate service vs embedded module (D14a) is unresolved.

## Validation

Spike S1: a minimal app on a domain-joined, target-like Windows server authenticates a domain user via Negotiate (Kerberos) in Edge, reads SID and groups, and resolves DataAdmin. **Not run yet.** The planning workstation is not domain-joined (see `docs/environment-inventory.md` §1).
