import { api } from './client.js';

// Database sessions (plan Phase 6). The browser signs in to Windows again on the open and list requests (the
// server answers 401 Negotiate first); that happens inside the browser and is invisible here.

export function listProfiles() {
  return api('/api/profiles').then((r) => r.profiles);
}

export function listDatabases(profile) {
  return api(`/api/profiles/${encodeURIComponent(profile)}/databases`, { method: 'POST' }).then((r) => r.databases);
}

/**
 * Opens a session. With `replaces` (the current session's ID) it switches database: the server opens the new session
 * first and closes the old one only if that worked, and refuses (TRANSACTION_OPEN) to discard an open transaction
 * unless `discardTransaction` is true.
 */
export function openSession(profile, database, { replaces, discardTransaction } = {}) {
  const body = { database };
  if (replaces) body.replaces = replaces;
  if (discardTransaction) body.discardTransaction = true;
  return api(`/api/db-sessions/${encodeURIComponent(profile)}`, { method: 'POST', body });
}

export function listSessions() {
  return api('/api/db-sessions').then((r) => r.sessions);
}

export function closeSession(id) {
  return api(`/api/db-sessions/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export function heartbeat(id) {
  return api(`/api/db-sessions/${encodeURIComponent(id)}/heartbeat`, { method: 'POST' });
}

/** Human wording for connection errors. */
export function describeConnectionError(error) {
  switch (error?.code) {
    case 'CONNECT_FAILED':
      return 'Could not connect to the database. Check that you have access to it, or ask the administrator.';
    case 'SUPERUSER_REFUSED':
      return 'Superuser roles cannot be used through this application.';
    case 'SESSION_LIMIT':
    case 'SERVER_BUSY':
    case 'RATE_LIMITED':
    case 'TRANSACTION_OPEN':
      return error.message;
    case 'SESSION_BUSY':
      return 'A statement is still running in this session. Wait for it to finish, or cancel it, then switch.';
    case 'INVALID_REPLACE':
      return 'A session can only be switched to another database on the same server.';
    case 'AUDIT_UNAVAILABLE':
      return 'The audit service is unavailable, so the server refused the request. Try again shortly.';
    case 'NETWORK':
      return 'The application server could not be reached. Check your network connection.';
    case 'DELEGATION_HANDSHAKE':
      return 'Windows sign-in for the database connection did not complete. Open the application by its full host name from a domain-joined computer.';
    case 'FORBIDDEN':
      return 'You are not allowed to use this database server.';
    default:
      return error?.message || 'The connection failed.';
  }
}
