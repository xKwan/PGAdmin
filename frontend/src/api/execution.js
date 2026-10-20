import { api } from './client.js';

// SQL execution (plan Phases 7–8). Everything that matters is enforced on the server: the single-statement gate,
// limits, the timeout, cancel and audit. This module only carries requests.

const base = (sessionId) => `/api/db-sessions/${encodeURIComponent(sessionId)}`;

/**
 * Runs one statement; resolves with the result document (docs/sql-execution-behaviour.md §4). A destructive
 * statement is refused with CONFIRMATION_REQUIRED until it is sent again with `confirmed`.
 */
export function execute(sessionId, sql, confirmed = false) {
  return api(`${base(sessionId)}/execute`, { method: 'POST', body: confirmed ? { sql, confirmed: true } : { sql } });
}

/**
 * Runs a script (EXE-03): the server splits it with PostgreSQL's parser, checks every statement, then runs them one
 * at a time and stops at the first that doesn't succeed. Resolves with a `kind: "script"` document.
 */
export function runScript(sessionId, sql, confirmed = false) {
  return api(`${base(sessionId)}/script`, { method: 'POST', body: confirmed ? { sql, confirmed: true } : { sql } });
}

/** The next page of a result whose rows continue on the server (`more`, inside a transaction only). */
export function fetchMore(sessionId, executionId) {
  return api(`${base(sessionId)}/fetch`, { method: 'POST', body: { executionId } });
}

/**
 * Records that rows the browser holds are being saved as a file (admin Phase 7). The server writes the audit record
 * (format and counts, never the data) before answering; without it the file is not saved.
 */
export function recordExport(sessionId, { executionId, format, rows, columns }) {
  return api(`${base(sessionId)}/export`, { method: 'POST', body: { executionId, format, rows, columns } });
}

/** Begin (optionally with an isolation level and read-only), commit or rollback. Resolves with a result document. */
export function transaction(sessionId, action, { isolation, readOnly = false } = {}) {
  const body = { action, readOnly };
  if (isolation) body.isolation = isolation;
  return api(`${base(sessionId)}/transaction`, { method: 'POST', body });
}

const REASONS = {
  drop: 'drops an object',
  truncate: 'empties a table',
  privileges: 'changes privileges',
  ddl: 'changes the database structure',
  no_where: 'updates or deletes rows without a WHERE clause',
  procedural: 'runs procedural code (DO)',
  explain_analyze: 'is EXPLAIN ANALYZE, which runs the statement to measure it: any change it makes is real',
};

/** Why a statement asks for confirmation, in words. */
export function describeReasons(reasons = []) {
  return reasons.map((r) => REASONS[r] || r);
}

/** Plain-language explanation of common PostgreSQL error codes (plan section 4); null if there is none. */
export function explainSqlstate(sqlstate, error = {}) {
  if (!sqlstate) return null;
  if (sqlstate === '55P03') return 'Could not obtain a lock in time. This statement changed nothing.';
  if (sqlstate === '40P01') return 'Deadlock detected. PostgreSQL rolled back the transaction; roll back here too, then try again.';
  if (sqlstate === '40001') return 'The transaction conflicted with a concurrent change. Roll back and try again; nothing is retried automatically.';
  if (sqlstate === '25P02') return 'The transaction is aborted. Roll back to continue.';
  if (sqlstate === '25001') return 'This statement cannot run inside a transaction. Commit or roll back first.';
  if (sqlstate === '25006') return 'The transaction is read-only.';
  if (sqlstate.startsWith('23')) return error.constraint ? `A constraint was violated: ${error.constraint}.` : 'A constraint was violated.';
  return null;
}

export function cancel(sessionId) {
  return api(`${base(sessionId)}/cancel`, { method: 'POST' });
}

/** What the running statement is doing: {running, elapsedMs, waitEventType, waitEvent, lockWait}. */
export function activity(sessionId) {
  return api(`${base(sessionId)}/activity`);
}

/** Human wording for a request the server refused before running anything. */
export function describeExecutionError(error) {
  switch (error?.code) {
    case 'MULTIPLE_STATEMENTS':
      return `${error.message} Select one statement, or put the cursor in it, and run again. To run them all, use Run script.`;
    case 'TOO_MANY_STATEMENTS':
      return `${error.message}. Split it into smaller scripts.`;
    case 'CURSOR_CLOSED':
      return 'The rest of these rows is no longer available (the transaction ended or another statement ran). Run the statement again.';
    case 'EMPTY_STATEMENT':
      return 'There is no statement to run.';
    case 'SYNTAX_ERROR':
      return error.message;
    case 'TOO_LARGE':
      return 'The statement is too long to run here.';
    case 'UNSUPPORTED':
    case 'GATE_DISAGREEMENT':
    case 'SESSION_BUSY':
      return error.message;
    case 'EXECUTION_DISABLED':
      return 'Running SQL is switched off by the administrator.';
    case 'AUDIT_UNAVAILABLE':
      return 'The statement was not run: the audit database is unavailable. Try again later.';
    case 'SESSION_LOST':
    case 'NOT_FOUND':
      return 'The database session has ended. Connect again.';
    case 'CONFIRMATION_REQUIRED':
      return `This statement ${describeReasons(error.details?.reasons).join(' and ') || 'needs a confirmation'}. It was not run.`;
    case 'INVALID_ACTION':
    case 'INVALID_ISOLATION':
      return error.message;
    default:
      return error?.message || 'The statement could not be run.';
  }
}

const STOPS = {
  error: 'it failed',
  timeout: 'it reached the time limit',
  cancelled: 'you cancelled it',
  indeterminate: 'the connection was lost while it ran',
  settings_changed: 'standard_conforming_strings changed, so the rest of the script could no longer be split safely',
  audit_unavailable: 'the audit database was unavailable, so it was not run',
  session_ended: 'the database session ended',
};

/** Why a script stopped, in words. */
export function describeScriptStop(reason) {
  return STOPS[reason] || reason;
}

/** What happened to a stop request (cancelOutcome). */
export function describeCancelOutcome(outcome) {
  switch (outcome) {
    case 'confirmed':
      return 'PostgreSQL stopped the statement.';
    case 'escalated_terminated':
      return 'The statement did not stop when asked, so its server process was ended. The session was closed.';
    case 'unconfirmed':
      return 'The statement could not be confirmed as stopped; the connection was closed. It may still run on the server until it finishes.';
    default:
      return null;
  }
}
