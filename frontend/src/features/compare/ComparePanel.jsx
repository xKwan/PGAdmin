import { useEffect, useId, useMemo, useState } from 'react';
import { describeMetadataError, getSnapshot, listSchemas, quoteIdent } from '../../api/metadata.js';
import { Spinner, StatusBadge } from '../../components/common/index.jsx';
import { compareSnapshots, NOT_COMPARED } from './schemaDiff.js';

// Schema comparison (admin Phase 8, decision A13): read a source schema, then compare a target schema with it, in
// the same database or, after switching database, in another. The result is a list of differences and SQL for a new
// editor tab. Nothing is run from here: the SQL runs only with Run script, through the gate, confirmation, kill switch
// and audit, as your own role.

const STATUS = {
  create: ['Only in source', 'info'],
  change: ['Different', 'warn'],
  drop: ['Only in target', 'neutral'],
  review: ['Review by hand', 'danger'],
};

const MAX_SQL_BYTES = 900_000;

function time(iso) {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleTimeString('en-GB');
}

function SchemaPicker({ label, schemas, value, onChange }) {
  const id = useId();
  return (
    <span className="manage-field compare-picker">
      <label htmlFor={id}>{label}</label>
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">Choose a schema</option>
        {schemas.map((s) => (
          <option key={s.name} value={s.name}>
            {s.name}
          </option>
        ))}
      </select>
    </span>
  );
}

/**
 * `source` is the snapshot read as the source (kept by the workspace, so it survives switching database);
 * `onSource` replaces or clears it; `onOpen(sql, title)` opens the SQL in a new editor tab.
 */
export function ComparePanel({ sessionId, database, source, onSource, onOpen }) {
  const [schemas, setSchemas] = useState(null);
  const [problem, setProblem] = useState(null);
  const [sourceSchema, setSourceSchema] = useState('');
  const [targetSchema, setTargetSchema] = useState('');
  const [target, setTarget] = useState(null);
  const [includeDrops, setIncludeDrops] = useState(false);
  const [busy, setBusy] = useState(null);
  const dropsId = useId();

  useEffect(() => {
    let live = true;
    listSchemas(sessionId).then(
      (r) => live && setSchemas(r.schemas),
      (e) => live && setProblem(describeMetadataError(e)),
    );
    return () => {
      live = false;
    };
  }, [sessionId]);

  const read = async (schema, then) => {
    setBusy(schema);
    setProblem(null);
    try {
      then(await getSnapshot(sessionId, schema));
    } catch (e) {
      setProblem(e?.code === 'SNAPSHOT_TOO_LARGE' ? e.message : describeMetadataError(e));
    } finally {
      setBusy(null);
    }
  };

  const result = useMemo(() => (source && target ? compareSnapshots(source, target, { includeDrops }) : null), [source, target, includeDrops]);
  const tooLarge = result && new TextEncoder().encode(result.sql).length > MAX_SQL_BYTES;
  const where = (b) => `${b.database} · ${b.schema}`;

  return (
    <section className="admin-panel compare-panel" aria-label="Schema comparison">
      <p className="muted admin-note">
        Read a source schema, then compare a target schema with it. To compare two databases, read the source, switch database in the session bar, and
        compare there: the source is kept until you clear it or leave the workspace. Nothing runs from here; the SQL opens in the editor.
      </p>
      {problem ? (
        <div className="result-error" role="alert">
          {problem}
        </div>
      ) : null}
      {schemas === null && !problem ? <Spinner label="Loading schemas" /> : null}
      {schemas ? (
        <div className="compare-steps">
          <fieldset className="compare-step">
            <legend>1. Source</legend>
            <SchemaPicker label={`Schema in ${database}`} schemas={schemas} value={sourceSchema} onChange={setSourceSchema} />
            <button
              type="button"
              className="btn"
              disabled={!sourceSchema || Boolean(busy)}
              onClick={() =>
                read(sourceSchema, (snap) => {
                  onSource(snap);
                  setTarget(null);
                })
              }
            >
              Read source
            </button>
            {source ? (
              <p>
                Source: <strong>{where(source)}</strong> <span className="muted">read at {time(source.loadedAt)}</span>{' '}
                <button
                  type="button"
                  className="btn btn-link"
                  onClick={() => {
                    onSource(null);
                    setTarget(null);
                  }}
                >
                  Clear
                </button>
              </p>
            ) : (
              <p className="muted">No source read yet.</p>
            )}
          </fieldset>
          <fieldset className="compare-step">
            <legend>2. Target</legend>
            <SchemaPicker label={`Schema in ${database}`} schemas={schemas} value={targetSchema} onChange={setTargetSchema} />
            <button type="button" className="btn btn-primary" disabled={!source || !targetSchema || Boolean(busy)} onClick={() => read(targetSchema, setTarget)}>
              Compare with source
            </button>
            {target ? (
              <p>
                Target: <strong>{where(target)}</strong> <span className="muted">read at {time(target.loadedAt)}</span>
              </p>
            ) : null}
          </fieldset>
        </div>
      ) : null}
      {busy ? <Spinner label={`Reading ${busy}`} /> : null}
      {result ? (
        <div className="compare-result">
          {result.differences.length === 0 ? (
            <p role="status">No differences in the compared objects.</p>
          ) : (
            <>
              <div className="admin-toolbar">
                <span>
                  {result.differences.length} difference{result.differences.length === 1 ? '' : 's'}: SQL that makes the target like the source.
                </span>
                <label className="manage-check" htmlFor={dropsId}>
                  <input id={dropsId} type="checkbox" checked={includeDrops} onChange={(e) => setIncludeDrops(e.target.checked)} />
                  Drop what exists only in the target (its data is lost)
                </label>
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={tooLarge}
                  onClick={() => onOpen(result.sql, `compare ${quoteIdent(source.schema)} to ${quoteIdent(target.schema)}.sql`)}
                >
                  Open SQL in editor
                </button>
              </div>
              {tooLarge ? (
                <p className="result-error" role="alert">
                  The SQL is larger than the server accepts in one script. Compare a smaller schema.
                </p>
              ) : null}
              <table className="grid admin-table">
                <caption>Differences</caption>
                <thead>
                  <tr>
                    <th scope="col">Object</th>
                    <th scope="col">Kind</th>
                    <th scope="col">Difference</th>
                    <th scope="col">Detail</th>
                  </tr>
                </thead>
                <tbody>
                  {result.differences.map((d, i) => (
                    <tr key={i}>
                      <td>{d.name}</td>
                      <td>{d.kind}</td>
                      <td>
                        <StatusBadge tone={STATUS[d.status][1]}>{STATUS[d.status][0]}</StatusBadge>
                      </td>
                      <td>{d.detail}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </div>
      ) : null}
      <p className="muted admin-note">Not compared: {NOT_COMPARED.join('; ')}.</p>
    </section>
  );
}
