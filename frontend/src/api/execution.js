import { api } from './client.js';

// SQL execution (plan Phases 7–8). Everything that matters is enforced on the server: the single-statement gate,
// limits, the timeout, cancel and audit. This module only carries requests.

const base = (sessionId) => `/api/db-sessions/${encodeURIComponent(sessionId)}`;

/** Runs one statement; resolves with the result document (docs/sql-execution-behaviour.md §4). */
export function execute(sessionId, sql) {
  return api(`${base(sessionId)}/execute`, { method: 'POST', body: { sql } });
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
      return `${error.message} Select one statement, or put the cursor in it, and run again.`;
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
    default:
      return error?.message || 'The statement could not be run.';
  }
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
