// API client (plan Phase 5). Same origin only; credentials are the session cookie plus Windows sign-in.
// - State-changing requests carry the session's CSRF token (from GET /api/me) and are JSON.
// - Errors use the server's envelope {"error":{"code","message","requestId"}} and surface as ApiError.
// - 401 means the app session is gone: a full reload makes the browser sign in again (Negotiate).

export class ApiError extends Error {
  /** `details` holds the envelope's extra fields (for example a syntax error's `position`). */
  constructor(status, code, message, requestId, details = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.requestId = requestId;
    this.details = details;
  }

  get kind() {
    if (this.status === 401 && this.code === 'DELEGATION_HANDSHAKE') return 'delegation';
    if (this.status === 401 || this.code === 'SESSION_RENEWED') return 'unauthenticated';
    if (this.status === 403) return 'forbidden';
    if (this.status === 503 && this.code === 'AUDIT_UNAVAILABLE') return 'audit-unavailable';
    if (this.status === 0) return 'network';
    return 'error';
  }
}

let csrfToken = null;
/** What to do on 401. Receives the ApiError; the default reloads the page to sign in again. */
const handlers = { unauthenticated: (_error) => window.location.reload() };

/** Remembers the CSRF token returned by /api/me (kept in memory only, never in browser storage). */
export function setCsrfToken(token) {
  csrfToken = token || null;
}

/** Test hook: what to do on 401 (default: reload the page to sign in again). */
export function setUnauthenticatedHandler(handler) {
  handlers.unauthenticated = handler;
}

const UNSAFE = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

async function readEnvelope(response) {
  try {
    const body = await response.json();
    if (body && body.error) return body.error;
  } catch {
    // not JSON (for example a connector-level error)
  }
  return { code: 'HTTP_' + response.status, message: response.statusText || 'Request failed' };
}

/**
 * Calls the API. Returns the parsed JSON body (or null for 204).
 * @param {string} path  must start with /api/
 * @param {{method?: string, body?: unknown, signal?: AbortSignal}} options
 */
export async function api(path, { method = 'GET', body, signal } = {}) {
  if (!path.startsWith('/api/')) throw new Error('api() only calls this application\'s /api/ endpoints');
  const headers = { Accept: 'application/json' };
  if (UNSAFE.has(method)) {
    headers['Content-Type'] = 'application/json';
    if (csrfToken) headers['X-CSRF-Token'] = csrfToken;
  }
  let response;
  try {
    // Resolved against this page's origin: always same-origin (and Node's fetch, in tests, needs an absolute URL).
    response = await fetch(new URL(path, window.location.origin), {
      method,
      headers,
      body: body === undefined ? (UNSAFE.has(method) ? '{}' : undefined) : JSON.stringify(body),
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      signal,
    });
  } catch (e) {
    if (e && e.name === 'AbortError') throw e;
    throw new ApiError(0, 'NETWORK', 'The server could not be reached', null);
  }
  if (response.status === 204) return null;
  if (response.ok) return response.json();
  const err = await readEnvelope(response);
  const { code, message, requestId, ...details } = err;
  const apiError = new ApiError(response.status, code, message, requestId ?? null, details);
  // DELEGATION_HANDSHAKE: the browser could not complete the Windows sign-in for a database connection; the app
  // session itself is fine, so this is an ordinary error, not a reason to reload.
  // SESSION_RENEWED (403): the app session expired and the browser signed in again by itself; reload to pick it up.
  if ((response.status === 401 && apiError.code !== 'DELEGATION_HANDSHAKE') || apiError.code === 'SESSION_RENEWED') {
    handlers.unauthenticated(apiError);
  }
  throw apiError;
}
