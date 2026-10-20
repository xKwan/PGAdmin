import { api } from './client.js';

// Saved queries on the server (admin Phase 11): your own, by Windows account. Saving runs nothing.

export function listSavedQueries() {
  return api('/api/saved-queries');
}

export function getSavedQuery(id) {
  return api(`/api/saved-queries/${encodeURIComponent(id)}`);
}

/** Creates a query, or replaces the one with `id`. */
export function saveQuery({ id = null, name, text }) {
  return id
    ? api(`/api/saved-queries/${encodeURIComponent(id)}`, { method: 'PUT', body: { name, text } })
    : api('/api/saved-queries', { method: 'POST', body: { name, text } });
}

export function deleteSavedQuery(id) {
  return api(`/api/saved-queries/${encodeURIComponent(id)}`, { method: 'DELETE' });
}
