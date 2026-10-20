import { api } from './client.js';

// Two-person approval (admin Phase 14). The server decides what needs approval and enforces it.

/** Asks a second person to approve exactly this text, for this session. Nothing runs. */
export function requestApproval(sessionId, sql, script) {
  return api(`/api/db-sessions/${encodeURIComponent(sessionId)}/approval`, { method: 'POST', body: { sql, script } });
}

/** Your requests, and (for approvers) the open requests of others. */
export function listApprovals() {
  return api('/api/approvals');
}

export function decideApproval(id, approve, comment = '') {
  return api(`/api/approvals/${encodeURIComponent(id)}/decision`, { method: 'POST', body: { approve, comment } });
}

export function withdrawApproval(id) {
  return api(`/api/approvals/${encodeURIComponent(id)}`, { method: 'DELETE' });
}
