import { useEffect, useId, useRef, useState } from 'react';
import { themeOf } from './preferences.js';
import { COMMANDS, describeKey, effectiveShortcuts, keyName, problemWith } from './shortcuts.js';

const THEME_LABELS = [
  ['system', 'Same as Windows'],
  ['light', 'Light'],
  ['dark', 'Dark'],
];

/**
 * Settings: the colour theme (admin Phase 12) and the editor's keyboard shortcuts (admin Phase 13). `prefs` and `onChange(next)` come from the app, which applies and
 * keeps them; `notSaved` says the browser refused to keep them. Escape or Close closes it.
 */
export default function SettingsDialog({ open, prefs, onChange, notSaved = false, onClose }) {
  const titleId = useId();
  const closeRef = useRef(null);
  const closeLatest = useRef(onClose);
  const [recording, setRecording] = useState(null); // the command whose new key is being pressed
  const [keyProblem, setKeyProblem] = useState(null);
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
  const keys = effectiveShortcuts(prefs?.shortcuts);
  const setKey = (id, key) => {
    const problem = problemWith(key, id, keys);
    if (problem) {
      setKeyProblem(problem);
      return;
    }
    setKeyProblem(null);
    setRecording(null);
    onChange({ ...prefs, shortcuts: { ...(prefs?.shortcuts || {}), [id]: key } });
  };
  const onRecordKey = (e, id) => {
    e.preventDefault();
    e.stopPropagation();
    if (e.key === 'Escape') {
      setRecording(null);
      setKeyProblem(null);
      return;
    }
    const key = keyName(e);
    if (key) setKey(id, key);
  };
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
          <fieldset>
            <legend>Keyboard shortcuts in the editor</legend>
            <table className="grid admin-table">
              <tbody>
                {COMMANDS.map((c) => (
                  <tr key={c.id}>
                    <th scope="row">{c.label}</th>
                    <td>{recording === c.id ? 'Press the new keys… (Escape to cancel)' : describeKey(keys[c.id])}</td>
                    <td className="admin-actions">
                      {recording === c.id ? (
                        <input
                          autoFocus
                          className="settings-recorder"
                          aria-label={`New keys for ${c.label}`}
                          readOnly
                          value=""
                          onKeyDown={(e) => onRecordKey(e, c.id)}
                        />
                      ) : (
                        <button type="button" className="btn btn-link" onClick={() => setRecording(c.id)} aria-label={`Change keys for ${c.label}`}>
                          Change
                        </button>
                      )}
                      <button type="button" className="btn btn-link" onClick={() => setKey(c.id, null)} aria-label={`No keys for ${c.label}`}>
                        None
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {keyProblem ? (
              <p className="result-error" role="alert">
                {keyProblem}
              </p>
            ) : null}
            <p>
              <button
                type="button"
                className="btn"
                onClick={() => {
                  setRecording(null);
                  setKeyProblem(null);
                  onChange({ ...prefs, shortcuts: {} });
                }}
              >
                Restore the default keys
              </button>
            </p>
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

