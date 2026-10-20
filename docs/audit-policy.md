# SQL Audit Policy (draft for approval)

> **Phase:** 0. **Status:** **Approved** 2026-10-15 (D4, D13): every §8 item as proposed (`docs/decision-log.md`).
> **Plan reference:** `docs/implementation-plan-v2.md` §6.

## 1. Purpose and scope

The audit trail records who did what, through this application, against which PostgreSQL database, and with what result. It covers actions taken **through pgweb only**. Activity by other clients (DBeaver, psql, jobs) is outside its scope and must be covered by PostgreSQL-side logging if required.

**The audit database is the only audit record.** Application logs and the local spool file are not audit records and are never treated as tamper-proof.

## 2. Events

| Event | When |
|---|---|
| `app_login`, `app_logout` | App session created or ended |
| `authz_denied` | Any request rejected by authorisation |
| `db_connect_attempt`, `db_connect_result` | DB session open requested, and its outcome (Kerberos; no credential recorded). For a database switch, `details.replaces` holds the ID of the session being replaced. |
| `db_disconnect`, `session_reaped`, `session_lost` | DB session closed, by the user, a timeout or a failure. `details.reason`: `user`, `switched` (replaced by a successful switch, after the new session's `db_connect_result`), `shutdown`, `idle`, … |
| `exec_intent` | **Before** SQL is sent to PostgreSQL. In a script (EXE-03), one per statement, written only after the previous statement ended; `details.scriptId`, `details.statementIndex` (0-based) and `details.statements` (how many) link it to the script |
| `exec_outcome` | After execution ends (success, error, timeout, cancelled, indeterminate); in a script it carries the same three details |
| `exec_rejected` | Text refused before anything was sent (gate, confirmation needed). For a script: `statement_class` `script`, the HMAC of the whole text, and `details.statementIndex` of the statement that was refused |
| `script_outcome` | After a script ends: HMAC and length of the whole text, `outcome` (`success`, or why it stopped: `error`, `timeout`, `cancelled`, `indeterminate`, `settings_changed`, `audit_unavailable`, `session_ended`), `details.scriptId`, `details.statements`, `details.completed`. Best effort, like `exec_outcome` |
| `exec_fetch` | **Before** a further page of a result is read from the server-side cursor (P9-3). Fail-closed, like `exec_intent`. `intent_id` is the statement's `execution_id`; `details.page`, `details.rowsBefore` |
| `exec_fetch_outcome` | After the page was read: outcome, SQLSTATE, rows in the page, truncated, duration. Best effort |
| `cancel_requested`, `cancel_result` | Cancel requested, and its outcome (`confirmed`, `escalated_terminated`, `unconfirmed`). Cancelling a script also records `details.scriptId` |
| `tx_commit`, `tx_rollback` | Explicit transaction control |
| `metadata_browse` | Metadata request (object path only; `details.object` is `schemas`, `objects`, `relation` or `extensions`, or, for the read-only administration views of admin Phase 3, `database`, `activity`, `sizes`, `roles` or `definition`; `details.schema` where one was named). Nothing that was shown is recorded |
| `session_signal` | **Before** another session's query is cancelled or the session ended (admin Phase 4). Fail-closed: no record, no signal. `details.action` (`cancel` or `terminate`), `details.targetPid`, `details.targetUser`, `details.targetDatabase`. An attempt PostgreSQL's rule doesn't allow is recorded with `outcome` `refused` and nothing is sent |
| `session_signal_outcome` | After the signal: `outcome` `success`, `not_sent` (the session had already gone) or `error` with its SQLSTATE; `details.action`, `details.targetPid`. Best effort |
| `data_export` | **Before** the browser saves rows it holds as a file (admin Phase 7). Fail-closed: no record, no file. `execution_id` of the statement the rows came from, `details.format` (`csv` or `json`), `details.rows`, `details.columns`. **Never the data.** Importing a CSV file creates no event of its own: the INSERT statements it produces are audited like any script |
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
- Purging requires two-person approval and follows a documented runbook. **Purging the online audit database is prohibited until all four conditions in `docs/operations/runbooks.md` §11 are met: anchor verification implemented and tested; a purge rehearsed on a test audit database with the chain verified from the anchor; the approved two-person procedure written into §11; and the stakeholder's written approval** (finding P12-3); the 12-month figure is a minimum online period, not permission to purge.
- Audit data is never dropped during upgrades or rollbacks; the audit DB is dumped and its chain verified before every upgrade.

## 8. Approval checklist

| Item | Proposed | Decision |
|---|---|---|
| SQL text mode per profile | Normalised + HMAC; raw text off | Approved 2026-10-15 |
| Identifiers stored | Yes | Approved 2026-10-15 |
| Fail mode | Fail-closed, both profiles | Approved 2026-10-15 |
| Audit DB location | Separate cluster/port | Approved 2026-10-15 |
| Retention online / archived | 12 months / 7 years | Approved 2026-10-15 |
| Auditor accounts | Named group | Approved 2026-10-15 |
| `metadata_browse` events recorded | Yes, object path only | Approved 2026-10-15 |
