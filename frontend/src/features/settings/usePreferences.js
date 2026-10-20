import { useCallback, useState } from 'react';
import { applyTheme, readPreferences, themeOf, writePreferences } from './preferences.js';

/** The app's display preferences: read once; each change is applied at once and kept in this browser if it allows. */
export default function usePreferences(storage) {
  const [prefs, setPrefs] = useState(() => readPreferences(storage));
  const [notSaved, setNotSaved] = useState(false);
  const change = useCallback(
    (next) => {
      setPrefs(next);
      applyTheme(themeOf(next));
      setNotSaved(!writePreferences(next, storage));
    },
    [storage],
  );
  return { prefs, change, notSaved };
}
