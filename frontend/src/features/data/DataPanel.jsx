import { useEffect, useMemo, useState } from 'react';
import { execute, runScript, transaction } from '../../api/execution.js';
import { describeMetadataError, describeRelation, qualified } from '../../api/metadata.js';
import { ConfirmDialog, Spinner } from '../../components/common/index.jsx';
import { browseSql, buildSave, describeKey, isEditable } from './rowEdit.js';
import { CsvError, IMPORT_MAX_BYTES, insertScript, parseCsv } from '../export/csv.js';

// The table data browser (admin Phase 6, decision A2). Rows are read with a generated SELECT through the normal
// execution path (gate, limits, audit, as your own role). Rows can be changed only in a table with a primary key and
// only with the privileges your role has; every save is shown first and runs as one transaction that is committed only
// if every statement affected exactly the row it was meant for.

export const PAGE = 100;

function problemText(e) {
  return e?.message || describeMetadataError(e);
}

/** The row form for editing or adding a row: one field per column, with NULL (and, for new rows, Default). */
function RowForm({ title, columns, values, isNew, onChange, onDone, onCancel }) {
  return (
    <fieldset className="data-row-form">
      <legend>{title}</legend>
      {columns.map((c) => {
        const v = values[c.name];
        const usesDefault = isNew && v === undefined;
        const editable = isEditable(c);
        return (
          <div className="manage-field" key={c.name}>
            <label htmlFor={`row-${c.name}`}>
              {c.name} <span className="muted">{c.type}</span>
            </label>
            <input
              id={`row-${c.name}`}
              value={v === null || v === undefined ? '' : v}
              disabled={!editable || v === null || usesDefault}
              onChange={(e) => onChange({ ...values, [c.name]: e.target.value })}
            />
            {editable && c.nullable ? (
              <label className="manage-check">
                <input type="checkbox" checked={v === null} onChange={(e) => onChange({ ...values, [c.name]: e.target.checked ? null : '' })} /> NULL
              </label>
            ) : null}
            {editable && isNew ? (
              <label className="manage-check">
                <input
                  type="checkbox"
                  checked={usesDefault}
                  onChange={(e) => {
                    const next = { ...values };
                    if (e.target.checked) delete next[c.name];
                    else next[c.name] = '';
                    onChange(next);
                  }}
                />{' '}
                Default
              </label>
            ) : null}
            {!editable ? <span className="muted">generated</span> : null}
          </div>
        );
      })}
      <div className="dialog-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="button" className="btn btn-primary" onClick={onDone}>
          Keep this change
        </button>
      </div>
    </fieldset>
  );
}

/**
 * `transaction`: the session's transaction state (`IDLE`, `OPEN`, `FAILED`); rows can be changed only when `IDLE`.
 * `onOpenSql(text)`: opens SQL in a new editor tab.
 */
export function DataPanel({ sessionId, schema, table, transaction: txState, onOpenSql }) {
  const [offset, setOffset] = useState(0);
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState({ key: null });
  const [changes, setChanges] = useState({});
  const [deleted, setDeleted] = useState([]);
  const [inserted, setInserted] = useState([]);
  const [form, setForm] = useState(null); // {row} | {insert: index} with values
  const [review, setReview] = useState(null); // statements
  const [notice, setNotice] = useState(null);
  const [saving, setSaving] = useState(false);
  const key = `${sessionId}|${schema}|${table}|${offset}|${attempt}`;

  useEffect(() => {
    let live = true;
    (async () => {
      try {
        const rel = await describeRelation(sessionId, schema, table);
        const sql = browseSql(schema, table, rel.columns, rel.primaryKey || [], PAGE, offset);
        const doc = await execute(sessionId, sql);
        if (live) setState({ key, rel, sql, doc });
      } catch (error) {
        if (live) setState({ key, error });
      }
    })();
    return () => {
      live = false;
    };
  }, [key, sessionId, schema, table, offset]);

  const loading = state.key !== key;
  const rel = loading ? null : state.rel;
  const doc = loading ? null : state.doc;
  const result = doc?.kind === 'rows' ? doc.results[0] : null;
  const rows = useMemo(() => (result ? result.rows.slice(0, PAGE) : []), [result]);
  const more = result ? result.rows.length > PAGE || result.more : false;
  const columns = rel ? rel.columns : [];
  const primaryKey = rel?.primaryKey || [];
  const privileges = new Set(rel?.myPrivileges || []);
  const truncated = useMemo(() => new Set((result?.truncatedCells || []).map(([r]) => r)), [result]);
  const pending = Object.keys(changes).length + deleted.length + inserted.length;
  const blocked =
    primaryKey.length === 0
      ? 'This table has no primary key, so its rows can be read here but not changed.'
      : txState && txState !== 'IDLE'
        ? 'Finish the open transaction first (Commit or Roll back): changes here run in their own transaction.'
        : null;
  const canUpdate = !blocked && privileges.has('UPDATE');
  const canDelete = !blocked && privileges.has('DELETE');
  const canInsert = !blocked && privileges.has('INSERT');

  // Import (admin Phase 7, decision A5): the file is read here, in the browser, and turned into INSERT statements
  // that open in a new editor tab; nothing is sent until the user runs them.
  const importFile = async (file) => {
    setNotice(null);
    try {
      if (file.size > IMPORT_MAX_BYTES) throw new CsvError(`The file is larger than 1 MB (${Math.ceil(file.size / 1024)} KB). Split it into smaller parts.`);
      const text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer());
      const script = insertScript(schema, table, columns, parseCsv(text), file.name);
      onOpenSql(script, `import-${table}.sql`);
      setNotice({ tone: 'ok', text: `${file.name}: the INSERT statements opened in a new editor tab. Review them, then use Run script.` });
    } catch (e) {
      setNotice({ tone: 'error', text: e instanceof CsvError ? `Not imported: ${e.message}` : 'Not imported: the file is not UTF-8 text.' });
    }
  };

  const reload = (keepNotice = false) => {
    setChanges({});
    setDeleted([]);
    setInserted([]);
    setForm(null);
    if (!keepNotice) setNotice(null);
    setAttempt((a) => a + 1);
  };

  const keepForm = () => {
    if (form.insert !== undefined) {
      setInserted((list) => {
        const next = [...list];
        next[form.insert] = form.values;
        return next;
      });
    } else {
      setChanges((c) => ({ ...c, [form.row]: form.values }));
    }
    setForm(null);
  };

  const openReview = () => {
    try {
      const statements = buildSave({ schema, table, columns, primaryKey, rows, changes, deleted, inserted });
      if (statements.length === 0) {
        setNotice({ tone: 'warn', text: 'Nothing to save: no value differs from what was loaded.' });
        return;
      }
      setReview(statements);
    } catch (e) {
      setNotice({ tone: 'error', text: e.message });
    }
  };

  const save = async () => {
    const statements = review;
    setReview(null);
    setSaving(true);
    const index = Object.fromEntries(columns.map((c, i) => [c.name, i]));
    try {
      const script = `BEGIN;\n${statements.map((s) => s.sql).join('\n')}`;
      const doc = await runScript(sessionId, script, true);
      const begun = doc.statements?.[0]?.kind === 'command';
      const results = (doc.statements || []).slice(1);
      const failed = results.findIndex((r) => r.kind !== 'command');
      const missed = results.findIndex((r, i) => statements[i].kind !== 'insert' && r.kind === 'command' && r.results?.[0]?.rowCount !== 1);
      if (doc.stopReason || failed >= 0 || missed >= 0) {
        if (begun) await transaction(sessionId, 'rollback');
        let why = 'PostgreSQL stopped the save.';
        if (failed >= 0 && results[failed].kind === 'error') why = `PostgreSQL refused it: ${results[failed].error.message}`;
        else if (missed >= 0) {
          const s = statements[missed];
          why = `the row with ${describeKey(primaryKey, rows[s.row], index)} was changed or deleted by someone else since it was loaded. Reload and try again.`;
        }
        setNotice({ tone: 'error', text: `Nothing was saved: ${why}` });
        return;
      }
      await transaction(sessionId, 'commit');
      const count = (k) => statements.filter((s) => s.kind === k).length;
      setNotice({ tone: 'ok', text: `Saved: ${count('update')} changed, ${count('insert')} added, ${count('delete')} deleted.` });
      reload(true);
    } catch (e) {
      // Saving starts only without an open transaction, so a rollback can only undo this save's own BEGIN, even when
      // the answer was lost before we knew whether it ran.
      try {
        await transaction(sessionId, 'rollback');
      } catch {
        // the session reports its own state; nothing more to do here
      }
      setNotice({ tone: 'error', text: `Nothing was saved: ${problemText(e)}` });
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="admin-panel data-panel" aria-label="Table data">
      <div className="admin-toolbar">
        <span>
          <strong>{qualified(schema, table)}</strong>{' '}
          <span className="muted">{rows.length ? `rows ${offset + 1}–${offset + rows.length}${primaryKey.length ? ', by primary key' : ''}` : ''}</span>
        </span>
        <button type="button" className="btn" disabled={loading || offset === 0} onClick={() => setOffset((o) => Math.max(0, o - PAGE))}>
          Previous {PAGE}
        </button>
        <button type="button" className="btn" disabled={loading || !more || pending > 0} onClick={() => setOffset((o) => o + PAGE)}>
          Next {PAGE}
        </button>
        <button type="button" className="btn" disabled={loading} onClick={() => reload()}>
          Reload
        </button>
        {state.sql ? (
          <button type="button" className="btn" onClick={() => onOpenSql(`${state.sql};\n`)}>
            Open query in editor
          </button>
        ) : null}
      </div>
      {loading ? <Spinner label="Loading rows" /> : null}
      {!loading && state.error ? (
        <p className="result-error" role="alert">
          {problemText(state.error)}
        </p>
      ) : null}
      {doc && doc.kind === 'error' ? (
        <p className="result-error" role="alert">
          {doc.error.message}
        </p>
      ) : null}
      {notice ? (
        <p className={notice.tone === 'error' ? 'result-error' : 'admin-note'} role={notice.tone === 'error' ? 'alert' : 'status'}>
          {notice.text}
        </p>
      ) : null}
      {result ? (
        <>
          {blocked ? (
            <p className="muted admin-note" role="note">
              {blocked}
            </p>
          ) : null}
          <div className="data-actions">
            {canInsert ? (
              <button type="button" className="btn" disabled={form !== null} onClick={() => setForm({ insert: inserted.length, values: {} })}>
                Add row
              </button>
            ) : null}
            {privileges.has('INSERT') ? (
              <label className="btn file-button">
                Import CSV…
                <input
                  type="file"
                  accept=".csv,text/csv"
                  className="visually-hidden"
                  onChange={(e) => {
                    const f = e.target.files?.[0];
                    e.target.value = '';
                    if (f) importFile(f);
                  }}
                />
              </label>
            ) : null}
            {pending > 0 ? (
              <>
                <span>
                  {pending} unsaved change{pending === 1 ? '' : 's'}
                </span>
                <button type="button" className="btn btn-primary" disabled={saving || form !== null} onClick={openReview}>
                  Review and save
                </button>
                <button type="button" className="btn" disabled={saving} onClick={() => reload()}>
                  Discard changes
                </button>
              </>
            ) : null}
          </div>
          {form ? (
            <RowForm
              title={form.insert !== undefined ? 'New row' : `Row with ${describeKey(primaryKey, rows[form.row], Object.fromEntries(columns.map((c, i) => [c.name, i])))}`}
              columns={columns}
              values={form.values}
              isNew={form.insert !== undefined}
              onChange={(values) => setForm({ ...form, values })}
              onDone={keepForm}
              onCancel={() => setForm(null)}
            />
          ) : null}
          <div className="grid-wrap">
            <table className="grid">
              <caption className="visually-hidden">Rows of {qualified(schema, table)}</caption>
              <thead>
                <tr>
                  {columns.map((c) => (
                    <th key={c.name} scope="col">
                      {c.name}
                      {primaryKey.includes(c.name) ? <span className="muted"> (key)</span> : null}
                    </th>
                  ))}
                  <th scope="col">
                    <span className="visually-hidden">Row actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row, r) => {
                  const edited = changes[r];
                  const gone = deleted.includes(r);
                  return (
                    <tr key={r} className={gone ? 'row-deleted' : edited ? 'row-edited' : undefined}>
                      {columns.map((c, i) => {
                        const v = edited && c.name in edited ? edited[c.name] : row[i];
                        return <td key={c.name}>{v === null ? <span className="null-marker">NULL</span> : v}</td>;
                      })}
                      <td className="admin-actions">
                        {gone ? (
                          <button type="button" className="btn btn-link" onClick={() => setDeleted((d) => d.filter((x) => x !== r))}>
                            Keep row {r + 1}
                          </button>
                        ) : (
                          <>
                            {canUpdate && !truncated.has(r) ? (
                              <button type="button" className="btn btn-link" aria-label={`Edit row ${r + 1}`} disabled={form !== null} onClick={() => setForm({ row: r, values: { ...Object.fromEntries(columns.map((c, i) => [c.name, row[i]])), ...(edited || {}) } })}>
                                Edit
                              </button>
                            ) : null}
                            {canDelete ? (
                              <button type="button" className="btn btn-link" aria-label={`Delete row ${r + 1}`} onClick={() => setDeleted((d) => [...d, r])}>
                                Delete
                              </button>
                            ) : null}
                          </>
                        )}
                      </td>
                    </tr>
                  );
                })}
                {inserted.map((values, i) => (
                  <tr key={`new-${i}`} className="row-edited">
                    {columns.map((c) => (
                      <td key={c.name}>{values[c.name] === undefined ? <span className="muted">default</span> : values[c.name] === null ? <span className="null-marker">NULL</span> : values[c.name]}</td>
                    ))}
                    <td className="admin-actions">
                      <button type="button" className="btn btn-link" onClick={() => setInserted((list) => list.filter((_, j) => j !== i))}>
                        Remove new row
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {truncated.size > 0 ? (
            <p className="result-truncated" role="note">
              Some values were too long to show in full; those rows can't be edited here.
            </p>
          ) : null}
        </>
      ) : null}
      <ConfirmDialog open={review !== null} title="Save these changes?" danger confirmLabel="Save" onConfirm={save} onCancel={() => setReview(null)}>
        <p>
          These statements run in one transaction, as your own role. It is committed only if each one affects exactly its row; otherwise nothing is
          saved. Each statement is recorded in the audit log.
        </p>
        <pre className="manage-sql" aria-label="Statements to run">
          {(review || []).map((s) => s.sql).join('\n')}
        </pre>
      </ConfirmDialog>
    </section>
  );
}
