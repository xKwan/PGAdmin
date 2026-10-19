import { useCallback, useEffect, useState } from 'react';
import { describeMetadataError, describeRelation, getDefinition, listExtensions, listObjects, listSchemas, qualified, quoteIdent } from '../../api/metadata.js';
import { Spinner, StatusBadge } from '../../components/common/index.jsx';
import { GrantList } from '../admin/AdminPanels.jsx';

const GROUPS = [
  ['table', 'Tables'],
  ['partitioned_table', 'Partitioned tables'],
  ['view', 'Views'],
  ['materialized_view', 'Materialized views'],
  ['foreign_table', 'Foreign tables'],
  ['sequence', 'Sequences'],
  ['function', 'Functions'],
  ['procedure', 'Procedures'],
  ['aggregate', 'Aggregates'],
  ['window', 'Window functions'],
];
const RELATIONS = new Set(['table', 'partitioned_table', 'view', 'materialized_view', 'foreign_table']);
/** Kinds whose definition the server can give (admin Phase 3). */
const DEFINABLE = new Set(['table', 'partitioned_table', 'view', 'materialized_view', 'sequence', 'function', 'procedure']);

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
      {data.owner ? (
        <p className="tree-meta">
          <span className="muted">Owner</span> <span>{data.owner}</span>
          {data.myPrivileges ? (
            <>
              {' · '}
              <span className="muted">Your privileges</span> <span>{data.myPrivileges.length ? data.myPrivileges.join(', ') : 'none'}</span>
            </>
          ) : null}
        </p>
      ) : null}
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
      {data.triggers?.length ? (
        <>
          <p className="tree-group-title">Triggers</p>
          <ul className="tree-list">
            {data.triggers.map((t) => (
              <li key={t.name} className="tree-leaf" title={t.definition}>
                <span className="tree-name">{t.name}</span>
                {t.enabled ? null : (
                  <>
                    {' '}
                    <StatusBadge tone="neutral">disabled</StatusBadge>
                  </>
                )}
              </li>
            ))}
          </ul>
        </>
      ) : null}
      {data.grants?.length ? (
        <>
          <p className="tree-group-title">Privileges</p>
          <GrantList grants={data.grants} label={`Privileges on ${schema}.${name}`} />
        </>
      ) : null}
    </div>
  );
}

/** Opens the object's definition (SQL text from the server) in a new editor tab; nothing is run. */
function DefinitionButton({ sessionId, schema, object, onOpen }) {
  const [state, setState] = useState({ loading: false, error: null });
  const label = `${schema}.${object.name}`;
  const open = () => {
    setState({ loading: true, error: null });
    getDefinition(sessionId, { kind: object.kind, schema, name: object.name, arguments: object.arguments }).then(
      (d) => {
        setState({ loading: false, error: null });
        onOpen(d.definition, `${object.name}.sql`);
      },
      (error) => setState({ loading: false, error }),
    );
  };
  return (
    <>
      <button
        type="button"
        className="btn btn-link tree-insert"
        onClick={open}
        disabled={state.loading}
        aria-label={`Open definition of ${label}`}
        title="Open its SQL definition in a new editor tab (nothing is run)"
      >
        Definition
      </button>
      {state.error ? (
        <span className="tree-error" role="alert">
          {describeMetadataError(state.error)}
        </span>
      ) : null}
    </>
  );
}

function ObjectNode({ sessionId, schema, object, version, onInsert, onOpen }) {
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
      {object.partition ? <StatusBadge tone="neutral" title="A partition of a partitioned table">partition</StatusBadge> : null}
      <InsertButton text={qualified(schema, object.name)} label={`${schema}.${object.name}`} onInsert={onInsert} />
      {onOpen && DEFINABLE.has(object.kind) && object.accessible !== false ? <DefinitionButton sessionId={sessionId} schema={schema} object={object} onOpen={onOpen} /> : null}
      {open ? <RelationDetails sessionId={sessionId} schema={schema} name={object.name} version={version} onInsert={onInsert} /> : null}
    </li>
  );
}

function SchemaNode({ sessionId, schema, all, version, onInsert, onOpen, filter }) {
  const [open, setOpen] = useState(false);
  return (
    <li className="tree-node">
      <button type="button" className="tree-toggle" aria-expanded={open} onClick={() => setOpen((o) => !o)} disabled={schema.accessible === false}>
        <span className="tree-caret" aria-hidden="true" />
        <span className="tree-name">{schema.name}</span>
      </button>
      <NoAccess accessible={schema.accessible} />
      {open ? <SchemaObjects sessionId={sessionId} schema={schema.name} all={all} version={version} onInsert={onInsert} onOpen={onOpen} filter={filter} /> : null}
    </li>
  );
}

function SchemaObjects({ sessionId, schema, all, version, onInsert, onOpen, filter = '' }) {
  const { data, error, loading, retry } = useLoad(() => listObjects(sessionId, schema, all), `${version}:${all}:${schema}`);
  if (loading) return <Spinner label={`Loading ${schema}`} />;
  if (error) return <LoadError error={error} onRetry={retry} />;
  if (data.objects.length === 0) return <p className="muted tree-empty">No objects you can use.</p>;
  const f = filter.trim().toLowerCase();
  const shown = f ? data.objects.filter((o) => o.name.toLowerCase().includes(f)) : data.objects;
  if (shown.length === 0) return <p className="muted tree-empty">No objects match the filter.</p>;
  return (
    <div className="tree-details">
      {GROUPS.map(([kind, title]) => {
        const items = shown.filter((o) => o.kind === kind);
        if (items.length === 0) return null;
        return (
          <div key={kind}>
            <p className="tree-group-title">{title}</p>
            <ul className="tree-list">
              {items.map((o) => (
                <ObjectNode key={`${o.kind}:${o.name}(${o.arguments ?? ''})`} sessionId={sessionId} schema={schema} object={o} version={version} onInsert={onInsert} onOpen={onOpen} />
              ))}
            </ul>
          </div>
        );
      })}
    </div>
  );
}

/** Extensions of the connected database, loaded when the group is opened. */
function Extensions({ sessionId, version }) {
  const { data, error, loading, retry } = useLoad(() => listExtensions(sessionId), `${version}:extensions`);
  if (loading) return <Spinner label="Loading extensions" />;
  if (error) return <LoadError error={error} onRetry={retry} />;
  if (data.extensions.length === 0) return <p className="muted tree-empty">No extensions installed.</p>;
  return (
    <ul className="tree-list">
      {data.extensions.map((e) => (
        <li key={e.name} className="tree-leaf" title={e.comment}>
          <span className="tree-name">{e.name}</span> <span className="muted">{e.version} · {e.schema}</span>
        </li>
      ))}
    </ul>
  );
}

/** A collapsible group of the tree. */
function Group({ title, children }) {
  const [open, setOpen] = useState(false);
  return (
    <li className="tree-node">
      <button type="button" className="tree-toggle" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        <span className="tree-caret" aria-hidden="true" />
        <span className="tree-name">{title}</span>
      </button>
      {open ? children : null}
    </li>
  );
}

/**
 * The object browser (plan Phase 10, FN-11; admin Phase 2): the connected database → schemas → objects (tables,
 * partitioned tables and their partitions, views, sequences, routines) → columns, indexes, constraints and triggers,
 * plus the database's extensions and the other databases you can open. Loaded lazily from the session's companion
 * connection as your own role. By default only objects you have privileges on are shown; "Show everything" lists all
 * the catalog shows you, marking those you can't use. Insert puts a correctly quoted name at the editor's cursor.
 * The tree shows committed objects only; `refreshKey` changes (after a committed change to objects) reload it.
 * Admin Phase 3: a name filter for the objects of opened schemas, a relation's owner, grants and your privileges,
 * and Definition, which hands the object's SQL to `onOpen(text, title)` (a new editor tab; nothing is run).
 */
export default function ObjectExplorer({ sessionId, onInsert, onOpen = null, database = null, databases = [], refreshKey = 0, onSwitch = null }) {
  const [all, setAll] = useState(false);
  const [filter, setFilter] = useState('');
  const [ownVersion, setVersion] = useState(0);
  const refresh = useCallback(() => setVersion((v) => v + 1), []);
  const version = `${refreshKey}.${ownVersion}`;
  const { data, error, loading, retry } = useLoad(() => listSchemas(sessionId, all), `${version}:${all}:${sessionId}`);
  const others = databases.filter((d) => d.name !== database);
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
      <input
        type="search"
        className="tree-filter"
        aria-label="Filter objects"
        placeholder="Filter objects by name"
        value={filter}
        onChange={(e) => setFilter(e.target.value)}
      />
      {loading ? <Spinner label="Loading schemas" /> : null}
      {error ? <LoadError error={error} onRetry={retry} /> : null}
      {database ? (
        <p className="tree-database" title="The database this session is connected to">
          <strong>{database}</strong> <span className="muted">(connected)</span>
        </p>
      ) : null}
      {data ? (
        <ul className="tree-list tree-root">
          {data.schemas.map((s) => (
            <SchemaNode key={`${version}:${all}:${s.name}`} sessionId={sessionId} schema={s} all={all} version={version} onInsert={onInsert} onOpen={onOpen} filter={filter} />
          ))}
          <Group title="Extensions">
            <Extensions sessionId={sessionId} version={version} />
          </Group>
          {others.length ? (
            <Group title="Other databases">
              <ul className="tree-list">
                {others.map((d) => (
                  <li key={d.name} className="tree-leaf">
                    <span className="tree-name">{d.name}</span>
                    {onSwitch ? (
                      <button type="button" className="btn btn-link tree-insert" onClick={() => onSwitch(d.name)} aria-label={`Switch to ${d.name}`}>
                        Switch
                      </button>
                    ) : null}
                  </li>
                ))}
              </ul>
            </Group>
          ) : null}
        </ul>
      ) : null}
      <p className="muted tree-note">Shows committed objects. Changes not yet committed in your transaction appear after commit and Refresh.</p>
    </section>
  );
}
