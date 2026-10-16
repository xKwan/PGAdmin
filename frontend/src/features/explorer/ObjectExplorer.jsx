import { useCallback, useEffect, useState } from 'react';
import { describeMetadataError, describeRelation, listObjects, listSchemas, qualified, quoteIdent } from '../../api/metadata.js';
import { Spinner, StatusBadge } from '../../components/common/index.jsx';

const GROUPS = [
  ['table', 'Tables'],
  ['view', 'Views'],
  ['materialized_view', 'Materialized views'],
  ['foreign_table', 'Foreign tables'],
  ['sequence', 'Sequences'],
  ['function', 'Functions'],
  ['procedure', 'Procedures'],
  ['aggregate', 'Aggregates'],
  ['window', 'Window functions'],
];
const RELATIONS = new Set(['table', 'view', 'materialized_view', 'foreign_table']);

function time(iso) {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleTimeString('en-GB');
}

/** Loads something once when first shown; `key` changes (refresh, "show all") load it again. */
function useLoad(load, key) {
  const [state, setState] = useState({ key: null, data: null, error: null });
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let live = true;
    load().then(
      (data) => live && setState({ key: `${key}#${attempt}`, data, error: null }),
      (error) => live && setState({ key: `${key}#${attempt}`, data: null, error }),
    );
    return () => {
      live = false;
    };
    // `load` is recreated every render; `key` and `attempt` say when to load again
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, attempt]);
  const current = state.key === `${key}#${attempt}`;
  return { data: current ? state.data : null, error: current ? state.error : null, loading: !current, retry: () => setAttempt((a) => a + 1) };
}

function LoadError({ error, onRetry }) {
  return (
    <div className="tree-error" role="alert">
      {describeMetadataError(error)}{' '}
      <button type="button" className="btn btn-link" onClick={onRetry}>
        Try again
      </button>
    </div>
  );
}

function InsertButton({ text, label, onInsert }) {
  return (
    <button type="button" className="btn btn-link tree-insert" onClick={() => onInsert(text)} aria-label={`Insert ${label}`} title={`Insert ${text} into the editor`}>
      Insert
    </button>
  );
}

function NoAccess({ accessible }) {
  return accessible === false ? <StatusBadge tone="neutral" title="Visible in the catalog, but you have no privilege on it">no access</StatusBadge> : null;
}

function RelationDetails({ sessionId, schema, name, version, onInsert }) {
  const { data, error, loading, retry } = useLoad(() => describeRelation(sessionId, schema, name), `${version}:${schema}.${name}`);
  if (loading) return <Spinner label={`Loading ${name}`} />;
  if (error) return <LoadError error={error} onRetry={retry} />;
  return (
    <div className="tree-details">
      <p className="tree-group-title">Columns</p>
      <ul className="tree-list">
        {data.columns.map((c) => (
          <li key={c.name} className="tree-leaf">
            <span className="tree-name">{c.name}</span> <span className="muted">{c.type}</span>
            {c.nullable ? null : <span className="muted"> NOT NULL</span>}
            {c.default ? <span className="muted"> default {c.default}</span> : null}
            <InsertButton text={quoteIdent(c.name)} label={`column ${c.name}`} onInsert={onInsert} />
          </li>
        ))}
      </ul>
      {data.indexes.length ? (
        <>
          <p className="tree-group-title">Indexes</p>
          <ul className="tree-list">
            {data.indexes.map((i) => (
              <li key={i.name} className="tree-leaf" title={i.definition}>
                <span className="tree-name">{i.name}</span>
                {i.primary ? <span className="muted"> primary key</span> : null}
                {!i.primary && i.unique ? <span className="muted"> unique</span> : null}
              </li>
            ))}
          </ul>
        </>
      ) : null}
      {data.constraints.length ? (
        <>
          <p className="tree-group-title">Constraints</p>
          <ul className="tree-list">
            {data.constraints.map((c) => (
              <li key={c.name} className="tree-leaf">
                <span className="tree-name">{c.name}</span> <span className="muted">{c.definition}</span>
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </div>
  );
}

function ObjectNode({ sessionId, schema, object, version, onInsert }) {
  const [open, setOpen] = useState(false);
  const expandable = RELATIONS.has(object.kind) && object.accessible !== false;
  const label = object.arguments === undefined ? object.name : `${object.name}(${object.arguments})`;
  return (
    <li className="tree-node">
      {expandable ? (
        <button type="button" className="tree-toggle" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
          <span className="tree-caret" aria-hidden="true" />
          <span className="tree-name">{label}</span>
        </button>
      ) : (
        <span className="tree-name tree-flat">{label}</span>
      )}
      <NoAccess accessible={object.accessible} />
      <InsertButton text={qualified(schema, object.name)} label={`${schema}.${object.name}`} onInsert={onInsert} />
      {open ? <RelationDetails sessionId={sessionId} schema={schema} name={object.name} version={version} onInsert={onInsert} /> : null}
    </li>
  );
}

function SchemaNode({ sessionId, schema, all, version, onInsert }) {
  const [open, setOpen] = useState(false);
  return (
    <li className="tree-node">
      <button type="button" className="tree-toggle" aria-expanded={open} onClick={() => setOpen((o) => !o)} disabled={schema.accessible === false}>
        <span className="tree-caret" aria-hidden="true" />
        <span className="tree-name">{schema.name}</span>
      </button>
      <NoAccess accessible={schema.accessible} />
      {open ? <SchemaObjects sessionId={sessionId} schema={schema.name} all={all} version={version} onInsert={onInsert} /> : null}
    </li>
  );
}

function SchemaObjects({ sessionId, schema, all, version, onInsert }) {
  const { data, error, loading, retry } = useLoad(() => listObjects(sessionId, schema, all), `${version}:${all}:${schema}`);
  if (loading) return <Spinner label={`Loading ${schema}`} />;
  if (error) return <LoadError error={error} onRetry={retry} />;
  if (data.objects.length === 0) return <p className="muted tree-empty">No objects you can use.</p>;
  return (
    <div className="tree-details">
      {GROUPS.map(([kind, title]) => {
        const items = data.objects.filter((o) => o.kind === kind);
        if (items.length === 0) return null;
        return (
          <div key={kind}>
            <p className="tree-group-title">{title}</p>
            <ul className="tree-list">
              {items.map((o) => (
                <ObjectNode key={`${o.kind}:${o.name}(${o.arguments ?? ''})`} sessionId={sessionId} schema={schema} object={o} version={version} onInsert={onInsert} />
              ))}
            </ul>
          </div>
        );
      })}
    </div>
  );
}

/**
 * The object browser (plan Phase 10, FN-11): schemas → objects → columns, indexes and constraints, loaded lazily from
 * the session's companion connection as your own role. By default only objects you have privileges on are shown;
 * "Show everything" lists all the catalog shows you, marking those you can't use. Insert puts a correctly quoted
 * name at the editor's cursor. The tree shows committed objects only.
 */
export default function ObjectExplorer({ sessionId, onInsert }) {
  const [all, setAll] = useState(false);
  const [version, setVersion] = useState(0);
  const refresh = useCallback(() => setVersion((v) => v + 1), []);
  const { data, error, loading, retry } = useLoad(() => listSchemas(sessionId, all), `${version}:${all}:${sessionId}`);
  return (
    <section className="object-explorer" aria-label="Objects">
      <div className="tree-toolbar">
        <span className="muted">{data ? `Loaded at ${time(data.loadedAt)}` : ' '}</span>
        <button type="button" className="btn" onClick={refresh}>
          Refresh
        </button>
      </div>
      <label className="tree-option">
        <input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} />{' '}
        Show everything in the catalog
      </label>
      {loading ? <Spinner label="Loading schemas" /> : null}
      {error ? <LoadError error={error} onRetry={retry} /> : null}
      {data ? (
        <ul className="tree-list tree-root">
          {data.schemas.map((s) => (
            <SchemaNode key={`${version}:${all}:${s.name}`} sessionId={sessionId} schema={s} all={all} version={version} onInsert={onInsert} />
          ))}
        </ul>
      ) : null}
      <p className="muted tree-note">Shows committed objects. Changes not yet committed in your transaction appear after commit and Refresh.</p>
    </section>
  );
}
