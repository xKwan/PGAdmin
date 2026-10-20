import { api } from './client.js';

/**
 * Searches the audit trail (admin Phase 9). The server reads it as you, so only auditors (pgweb_audit_reader) see
 * anything, and records the search first. Filters travel in the body, not the URL.
 */
export function searchAudit(filters) {
  return api('/api/audit/search', { method: 'POST', body: filters });
}
