// The last database a user opened on a server, remembered in this browser as a convenience (admin Phase 1). Only the
// database name is kept, per user SID and profile: never credentials, tokens or session IDs (SEC-08). Storage may be
// unavailable (private window, blocked site data): then nothing is remembered and nothing breaks.

const PREFIX = 'pgweb.lastDatabase';

function key(profile, sid) {
  return `${PREFIX}.${profile}.${sid || 'unknown'}`;
}

export function getLastDatabase(profile, sid) {
  try {
    return window.localStorage.getItem(key(profile, sid)) || null;
  } catch {
    return null;
  }
}

export function setLastDatabase(profile, sid, database) {
  try {
    window.localStorage.setItem(key(profile, sid), database);
  } catch {
    // storage unavailable: nothing to remember
  }
}
