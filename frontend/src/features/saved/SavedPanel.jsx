import { useCallback, useEffect, useId, useState } from 'react';
import { deleteSavedQuery, getSavedQuery, listSavedQueries, saveQuery } from '../../api/savedQueries.js';
import { ConfirmDialog, Spinner } from '../../components/common/index.jsx';

// Saved queries stored on the server (admin Phase 11, decision A16): your own, by your Windows account; nobody else
// sees them. Opening one puts its text in a new editor tab; nothing runs.

function when(iso) {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('en-GB');
}

/** The list, in the lower tabs. `version` changes when a query was saved elsewhere (the toolbar dialog). */
export function SavedPanel({ version, onOpen }) {
  const [state, setState] = useState({ queries: null, error: null });
  const [removing, setRemoving] = useState(null);
  const [notice, setNotice] = useState(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let live = true;
    listSavedQueries().then(
      (r) => live && setState({ queries: r.queries, error: null }),
      (error) => live && setState({ queries: null, error }),
    );
    return () => {
      live = false;
    };
  }, [version, reload]);

  const open = async (q) => {
    setNotice(null);
    try {
      const full = await getSavedQuery(q.id);
      onOpen(full.text, `${full.name}.sql`, full.id);
    } catch (error) {
      setNotice({ tone: 'error', text: error.message });
    }
  };
  const remove = async () => {
    const q = removing;
    setRemoving(null);
    try {
      await deleteSavedQuery(q.id);
      setNotice({ tone: 'info', text: `Deleted “${q.name}”.` });
      setReload((n) => n + 1);
    } catch (error) {
      setNotice({ tone: 'error', text: error.message });
    }
  };

  return (
    <section className="admin-panel" aria-label="Saved queries">
      <p className="muted admin-note">Your saved queries are kept on the pgweb server for your Windows account only. Don’t save passwords or personal data in them.</p>
      {notice ? (
        <p className={notice.tone === 'error' ? 'result-error' : 'muted'} role={notice.tone === 'error' ? 'alert' : 'status'}>
          {notice.text}
        </p>
      ) : null}
      {state.error ? (
        <div className="result-error" role="alert">
          {state.error.message}
        </div>
      ) : null}
      {!state.queries && !state.error ? <Spinner label="Loading saved queries" /> : null}
      {state.queries && state.queries.length === 0 ? <p>No saved queries yet. Use Save to server in the editor toolbar.</p> : null}
      {state.queries && state.queries.length > 0 ? (
        <table className="grid admin-table">
          <caption>Saved queries</caption>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Saved</th>
              <th scope="col">Characters</th>
              <th scope="col">
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {state.queries.map((q) => (
              <tr key={q.id}>
                <td>{q.name}</td>
                <td>{when(q.updatedAt)}</td>
                <td>{q.length}</td>
                <td className="admin-actions">
                  <button type="button" className="btn btn-link" onClick={() => open(q)} aria-label={`Open ${q.name}`}>
                    Open
                  </button>
                  <button type="button" className="btn btn-link" onClick={() => setRemoving(q)} aria-label={`Delete ${q.name}`}>
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      <ConfirmDialog open={Boolean(removing)} title="Delete this saved query?" confirmLabel="Delete" danger onConfirm={remove} onCancel={() => setRemoving(null)}>
        <p>“{removing?.name}” is deleted from the server. Editor tabs that show it keep their text.</p>
      </ConfirmDialog>
    </section>
  );
}

/**
 * The Save to server dialog, mounted while it is open (give it a new `key` for another tab). `initial` is
 * {name, savedId} of the tab; `getText()` reads the tab's text when saving. A tab opened from a saved query replaces
 * it, unless "Save as a new query" is chosen.
 */
export function SaveDialog({ initial, getText, onSaved, onClose }) {
  const [name, setName] = useState(initial?.name || '');
  const [asNew, setAsNew] = useState(false);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const nameId = useId();
  const newId = useId();

  const save = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      const id = initial?.savedId && !asNew ? initial.savedId : null;
      const saved = await saveQuery({ id, name, text: getText() });
      onSaved(saved);
    } catch (e) {
      setError(e.code === 'NAME_TAKEN' ? 'You already have a saved query with this name: choose another.' : e.message);
    } finally {
      setBusy(false);
    }
  }, [initial, asNew, name, getText, onSaved]);

  return (
    <div className="dialog-backdrop">
      <div className="dialog" role="dialog" aria-modal="true" aria-label="Save to server">
        <h2 className="dialog-title">Save to server</h2>
        <div className="dialog-body manage-form">
          <span className="manage-field">
            <label htmlFor={nameId}>Name</label>
            <input id={nameId} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
          </span>
          {initial?.savedId ? (
            <label className="manage-check" htmlFor={newId}>
              <input id={newId} type="checkbox" checked={asNew} onChange={(e) => setAsNew(e.target.checked)} /> Save as a new query (otherwise the saved one is replaced)
            </label>
          ) : null}
          <p className="muted">Saved for your Windows account only. Nothing is run.</p>
          {error ? (
            <p className="result-error" role="alert">
              {error}
            </p>
          ) : null}
        </div>
        <div className="dialog-actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="button" className="btn btn-primary" onClick={save} disabled={busy || !name.trim()}>
            Save
          </button>
        </div>
      </div>
    </div>
  );
}
