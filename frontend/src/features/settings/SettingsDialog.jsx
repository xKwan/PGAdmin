import { useEffect, useId, useRef } from 'react';
import { themeOf } from './preferences.js';

const THEME_LABELS = [
  ['system', 'Same as Windows'],
  ['light', 'Light'],
  ['dark', 'Dark'],
];

/**
 * Settings (admin Phase 12): the colour theme. `prefs` and `onChange(next)` come from the app, which applies and
 * keeps them; `notSaved` says the browser refused to keep them. Escape or Close closes it.
 */
export default function SettingsDialog({ open, prefs, onChange, notSaved = false, onClose }) {
  const titleId = useId();
  const closeRef = useRef(null);
  const closeLatest = useRef(onClose);
  useEffect(() => {
    closeLatest.current = onClose;
  });

  useEffect(() => {
    if (!open) return undefined;
    closeRef.current?.focus();
    const onKey = (e) => {
      if (e.key === 'Escape') closeLatest.current?.();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open]);

  if (!open) return null;
  const theme = themeOf(prefs);
  return (
    <div className="dialog-backdrop">
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <h2 id={titleId} className="dialog-title">
          Settings
        </h2>
        <div className="dialog-body settings-form">
          <fieldset>
            <legend>Colours</legend>
            {THEME_LABELS.map(([value, label]) => (
              <label key={value} className="manage-check">
                <input type="radio" name="theme" value={value} checked={theme === value} onChange={() => onChange({ ...prefs, theme: value })} /> {label}
              </label>
            ))}
          </fieldset>
          <p className="muted">Settings are kept in this browser only.</p>
          {notSaved ? (
            <p className="result-notice" role="status">
              This browser doesn’t allow saving settings: they last until the page is closed.
            </p>
          ) : null}
        </div>
        <div className="dialog-actions">
          <button type="button" className="btn" ref={closeRef} onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}

