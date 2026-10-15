# SQL Audit Policy (draft for approval)

> **Phase:** 0. **Status:** Proposed; every policy choice below is **Unresolved** until approved (decisions D4 and D13).
> **Plan reference:** `docs/implementation-plan-v2.md` §6.

## 1. Purpose and scope

The audit trail records who did what, through this application, against which PostgreSQL database, and with what result. It covers actions taken **through pgweb only**. Activity by other clients (DBeaver, psql, jobs) is outside its scope and must be covered by PostgreSQL-side logging if required.

**The audit database is the only audit record.** Application logs and the local spool file are not audit records and are never treated as tamper-proof.

## 2. Events

| Event | When |
|---|---|
| `app_login`, `app_logout` | App session created or ended |
| `authz_denied` | Any request rejected by authorisation |
| `db_connect_attempt`, `db_connect_result` | DB session open requested, and its outcome (no password) |
| `db_disconnect`, `session_reaped`, `session_lost` | DB session closed, by the user, a timeout or a failure |
| `exec_intent` | **Before** SQL is sent to PostgreSQL |
| `exec_outcome` | After execution ends (success, error, timeout, cancelled, indeterminate) |
| `cancel_requested`, `cancel_result` | Cancel requested, and its outcome (`confirmed`, `escalated_terminated`, `unconfirmed`) |
| `tx_commit`, `tx_rollback` | Explicit transaction control |
| `metadata_browse` | Metadata request (object path only) |
| `audit_spool_replayed`, `audit_failure` | Audit infrastructure events |

## 3. Fields

- **Identity:** Windows username, SID, DataAdmin flag (as evaluated by the server).
- **Target:** profile (`internal`/`external`), database name, PostgreSQL `session_user`, backend PID.
- **Correlation:** `requestId`, `executionId`, `dbSessionId`, app-session hash (never the raw session ID).
- **Statement:** statement class, normalised text (§4), `HMAC-SHA256` of the full text, text length, parse status.
- **Outcome:** result kind, cancel outcome, SQLSTATE, affected or returned row count, truncated flag, duration, transaction state before and after.
- **Time and source:** UTC timestamps (intent, start, end), client IP.
- **Integrity:** `prev_hash`, `row_hash`.

## 4. SQL text handling (proposed default)

| Case | Stored |
|---|---|
| Parser available and text parses | Normalised text (literals → `$1…$n`, comments removed) + HMAC of full text |
| Text does not parse (syntax error) | HMAC, length and SQLSTATE only. **No text.** |
| Parser unavailable (spike S2 failed) | HMAC and metadata only. **No text.** |
| Full raw text | **Off.** Per-profile opt-in only with explicit approval, because raw text captures passwords, tokens and personal data. |

- The HMAC key is held on the server (DPAPI-protected config) and rotated by runbook. A keyed hash prevents dictionary attacks on short literals while still letting investigators confirm a known statement.
- Identifiers (schema, table, column names) remain visible in normalised text. **Assumed acceptable; needs confirmation.**
- **Normalisation method (S2, Tested for PG 18):**
  - libpg_query's `pg_query_normalize` is **not** used. It leaves literals in place in 7 of the 28 statement forms tested (`COMMENT ON`, `COPY … PROGRAM`, `PREPARE`, `EXECUTE`, server `OPTIONS`, column `DEFAULT`, `BEGIN ATOMIC` bodies).
  - Instead, normalised text is produced with PostgreSQL's **scanner**: comment tokens are removed and every constant token becomes `$n`. That left no literal and no comment in any of the 28 forms (`spikes/s2-parser/REPORT.md`, N2/N3).
  - The scanner can't be told that `standard_conforming_strings` is off. For sessions where it is off, only the HMAC and metadata are stored.

## 5. Write path and failure behaviour

1. `exec_intent` must commit to the audit DB **before** the SQL is sent.
2. `exec_outcome` is written after execution. If that write fails, the record goes to the local spool (append-only JSONL, hash-chained, NTFS ACL: service account write, auditors read), an alert is raised, and a reconciler replays it when the DB is back.
3. **Uncertain outcomes:**
   - Execution cut off by a socket loss or an unconfirmed cancel is recorded as **`indeterminate` (may have committed)**, never as failure.
   - Intents with no outcome are reported by the reconciler as **`outcome_unknown`**.

**Fail mode (D4, requires approval):**

| Policy | When the intent can't be persisted | Consequence |
|---|---|---|
| **Fail-closed (recommended)** | Execution refused with `503 AUDIT_UNAVAILABLE`; connect/disconnect still allowed | No unaudited changes. An audit outage stops SQL execution, so the audit DB is critical infrastructure. |
| Fail-open | Execution continues; intent spooled locally | Unaudited changes possible during outages; local spool is easier to tamper with |
| Hybrid | Fail-closed for internal, fail-open for external | Mixed |

The fail mode is one configuration switch, tested in both positions.

## 6. Integrity and access

- **Roles:** `pgweb_migrator` (deploy-time owner), `pgweb_app` (INSERT only), `pgweb_audit_reader` (SELECT, named auditors).
- A trigger rejects UPDATE and DELETE. A hash chain makes edits and deletions detectable; a verification job checks it.
- **Location (D4):** a separate PostgreSQL cluster/port, not administered by DataAdmin superusers, is recommended. If co-hosted on 5433, the trail is **tamper-evident only**, not tamper-proof.
- No audit UI in the MVP; auditors query through `pgweb_audit_reader`.

## 7. Retention and rotation (D13, requires approval)

- Monthly declarative partitions.
- Proposed placeholders: **12 months online, 7 years archived**. To be aligned with the actual legal and policy requirement.
- Closed partitions are exported daily to an ACL-restricted share with a SHA-256 manifest.
- Purging requires two-person approval and follows a documented runbook.
- Audit data is never dropped during upgrades or rollbacks; the audit DB is dumped and its chain verified before every upgrade.

## 8. Approval checklist

| Item | Proposed | Decision |
|---|---|---|
| SQL text mode per profile | Normalised + HMAC; raw text off | Unresolved |
| Identifiers stored | Yes | Unresolved |
| Fail mode | Fail-closed, both profiles | Unresolved |
| Audit DB location | Separate cluster/port | Unresolved |
| Retention online / archived | 12 months / 7 years | Unresolved |
| Auditor accounts | Named group | Unresolved |
| `metadata_browse` events recorded | Yes, object path only | Unresolved |
