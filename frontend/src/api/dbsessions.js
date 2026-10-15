import { api } from './client.js';

// Database sessions (plan Phase 6). The browser signs in to Windows again on the open and list requests (the
// server answers 401 Negotiate first); that happens inside the browser and is invisible here.

export function listProfiles() {
  return api('/api/profiles').then((r) => r.profiles);
}

export function listDatabases(profile) {
  return api(`/api/profiles/${encodeURIComponent(profile)}/databases`, { method: 'POST' }).then((r) => r.databases);
}

export function openSession(profile, database) {
  return api(`/api/db-sessions/${encodeURIComponent(profile)}`, { method: 'POST', body: { database } });
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
      return error.message;
    case 'DELEGATION_HANDSHAKE':
      return 'Windows sign-in for the database connection did not complete. Open the application by its full host name from a domain-joined computer.';
    case 'FORBIDDEN':
      return 'You are not allowed to use this database server.';
    default:
      return error?.message || 'The connection failed.';
  }
}
