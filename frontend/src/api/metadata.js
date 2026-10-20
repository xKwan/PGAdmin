import { api } from './client.js';

// Object browser (plan Phase 10). Names are sent only as query parameters; the server binds them as parameters.

const base = (sessionId) => `/api/metadata/${encodeURIComponent(sessionId)}`;
const flag = (all) => (all ? '&all=true' : '');

export function listSchemas(sessionId, all = false) {
  return api(`${base(sessionId)}/schemas?${all ? 'all=true' : ''}`);
}

export function listObjects(sessionId, schema, all = false) {
  return api(`${base(sessionId)}/objects?schema=${encodeURIComponent(schema)}${flag(all)}`);
}

export function describeRelation(sessionId, schema, name) {
  return api(`${base(sessionId)}/relation?schema=${encodeURIComponent(schema)}&name=${encodeURIComponent(name)}`);
}

/** Extensions installed in the connected database. */
export function listExtensions(sessionId) {
  return api(`${base(sessionId)}/extensions`);
}

// Read-only administration (admin Phase 3), read as the user's own role.

/** The connected database: properties, your privileges, grants. */
export function getDatabaseInfo(sessionId) {
  return api(`${base(sessionId)}/database`);
}

/** Client sessions on the server (as far as your role may see them) and the locks of other sessions. */
export function getActivity(sessionId) {
  return api(`${base(sessionId)}/activity`);
}

/**
 * Cancels another session's running query (`cancel`) or ends that session (`terminate`), admin Phase 4. The server
 * checks the role's privileges, asks for `confirmed`, and records it in the audit log before sending the signal.
 */
export function signalSession(sessionId, pid, action, confirmed = false) {
  return api(`${base(sessionId)}/signal`, { method: 'POST', body: { pid, action, confirmed } });
}

/** Sizes of the databases you may connect to and of the largest tables you can use. */
export function getSizes(sessionId) {
  return api(`${base(sessionId)}/sizes`);
}

/** Roles and memberships; `all` includes the predefined pg_* roles. */
export function getRoles(sessionId, all = false) {
  return api(`${base(sessionId)}/roles${all ? '?all=true' : ''}`);
}

/**
 * A schema's structure for schema comparison (admin Phase 8): tables with columns, constraints, indexes and triggers,
 * views, sequences and routines, with names in that schema written without it. Read-only.
 */
export function getSnapshot(sessionId, schema) {
  return api(`${base(sessionId)}/snapshot?schema=${encodeURIComponent(schema)}`);
}

/** An object's definition as SQL text (never run). Routines are chosen by their identity arguments. */
export function getDefinition(sessionId, { kind, schema, name, arguments: args }) {
  const q = new URLSearchParams({ kind, schema, name });
  if (args !== undefined) q.set('arguments', args);
  return api(`${base(sessionId)}/definition?${q}`);
}

// PostgreSQL keywords that can't be used as bare identifiers (reserved, and reserved-but-allowed-as-function/type).
const RESERVED = new Set(
  (
    'all analyse analyze and any array as asc asymmetric authorization binary both case cast check collate collation column ' +
    'concurrently constraint create cross current_catalog current_date current_role current_schema current_time ' +
    'current_timestamp current_user default deferrable desc distinct do else end except false fetch for foreign freeze ' +
    'from full grant group having ilike in initially inner intersect into is isnull join lateral leading left like limit ' +
    'localtime localtimestamp natural not notnull null offset on only or order outer overlaps placing primary references ' +
    'returning right select session_user similar some symmetric system_user table tablesample then to trailing true ' +
    'union unique user using variadic verbose when where window with'
  ).split(' '),
);

/** An identifier as SQL: bare when it is lower-case, simple and not a keyword; otherwise quoted, with " doubled. */
export function quoteIdent(name) {
  if (/^[a-z_][a-z0-9_$]*$/.test(name) && !RESERVED.has(name)) return name;
  return `"${name.replaceAll('"', '""')}"`;
}

export function qualified(schema, name) {
  return `${quoteIdent(schema)}.${quoteIdent(name)}`;
}

/** Human wording for object-browser errors. */
export function describeMetadataError(error) {
  switch (error?.code) {
    case 'COMPANION_BUSY':
      return 'The session is busy loading other information. Try again in a moment.';
    case 'METADATA_UNAVAILABLE':
      return 'Object browsing is not available for this session (Limited). Connect again later to browse objects.';
    case 'NOT_FOUND':
      return 'The object no longer exists, or you can no longer see it. Refresh the tree.';
    default:
      return error?.message || 'The objects could not be loaded.';
  }
}
