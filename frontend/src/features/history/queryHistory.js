// Query history (admin Phase 2; decided 2026-10-19: "this browser tab only"). The user's own statements and scripts,
// full text, newest first, at most MAX_ENTRIES, kept in sessionStorage per user (SID) and server profile: gone when
// the tab closes, never sent anywhere, and separate from the audit trail (which stores no literals). Storage can be
// unavailable or full; history then degrades (fewer entries, or none) without affecting anything else.

export const MAX_ENTRIES = 50;

function key(profile, sid) {
  return `pgweb.history.${profile}.${sid || 'unknown'}`;
}

function store(win) {
  try {
    return win.sessionStorage;
  } catch {
    return null; // blocked by the browser's settings
  }
}

/** The entries, newest first ([] if none or storage is unavailable). */
export function readHistory(profile, sid, win = window) {
  const s = store(win);
  if (!s) return [];
  try {
    const parsed = JSON.parse(s.getItem(key(profile, sid)) || '[]');
    return Array.isArray(parsed) ? parsed.filter((e) => e && typeof e.text === 'string') : [];
  } catch {
    return [];
  }
}

/**
 * Adds an entry at the top and returns the new list. When storage is full, the oldest entries are dropped until it
 * fits; an entry too large to store at all is kept for this page only.
 */
export function addHistory(profile, sid, entry, win = window) {
  const list = [entry, ...readHistory(profile, sid, win)].slice(0, MAX_ENTRIES);
  const s = store(win);
  if (!s) return list;
  for (let keep = list.length; keep > 0; keep -= 1) {
    try {
      s.setItem(key(profile, sid), JSON.stringify(list.slice(0, keep)));
      return list;
    } catch {
      // QuotaExceededError: try with fewer entries
    }
  }
  return list;
}

export function clearHistory(profile, sid, win = window) {
  try {
    store(win)?.removeItem(key(profile, sid));
  } catch {
    // nothing to clear
  }
}

/** A history entry for a finished run. `outcome`: success, error, refused, cancelled, timeout, stopped, unknown. */
export function historyEntry({ text, database, script = false, result = null, error = null, now = new Date() }) {
  let outcome = 'success';
  if (error) outcome = 'refused';
  else if (script && result?.stopReason) outcome = result.stopReason === 'error' ? 'error' : 'stopped';
  else if (result?.kind === 'error') outcome = 'error';
  else if (result?.kind === 'timeout' || result?.kind === 'cancelled') outcome = result.kind;
  else if (result?.kind === 'indeterminate') outcome = 'unknown';
  return {
    id: `${now.getTime()}-${Math.random().toString(36).slice(2, 8)}`,
    at: now.toISOString(),
    text,
    database,
    script,
    outcome,
    durationMs: result?.durationMs ?? null,
    statements: script ? (result?.total ?? null) : null,
  };
}
