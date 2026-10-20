// Display preferences (admin Phases 12 and 13, decisions A17 and A18): the colour theme and keyboard shortcuts. They
// are conveniences of this browser, so they live in its local storage under one key; nothing is sent to the server.
// Storage can be missing or blocked (private windows, policies): every read and write tolerates that.

const KEY = 'pgweb.preferences.v1';

export const THEMES = ['system', 'light', 'dark'];

export function readPreferences(storage = globalThis.localStorage) {
  try {
    const raw = storage?.getItem(KEY);
    const p = raw ? JSON.parse(raw) : {};
    return p && typeof p === 'object' && !Array.isArray(p) ? p : {};
  } catch {
    return {};
  }
}

export function writePreferences(p, storage = globalThis.localStorage) {
  try {
    storage?.setItem(KEY, JSON.stringify(p));
    return true;
  } catch {
    return false;
  }
}

export function themeOf(p) {
  return THEMES.includes(p?.theme) ? p.theme : 'system';
}

/** Puts the theme on the page: `data-theme` on <html> for light or dark; none follows the system setting. */
export function applyTheme(theme, root = globalThis.document?.documentElement) {
  if (!root) return;
  if (theme === 'light' || theme === 'dark') root.setAttribute('data-theme', theme);
  else root.removeAttribute('data-theme');
}
