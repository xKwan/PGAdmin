import { useId, useMemo, useState } from 'react';
import * as gen from './sqlGen.js';

// The management screens (admin Phase 5, decision A1): pick what to do, fill in a form, check the SQL, and open it in
// a new editor tab. Nothing runs from here: the SQL runs only with Run or Run script, through the server's gate,
// confirmation, kill switch and audit, as your own role, so PostgreSQL decides what your role may do.

const text = (key, label, extra = {}) => ({ key, label, type: 'text', ...extra });
const area = (key, label, extra = {}) => ({ key, label, type: 'textarea', ...extra });
const check = (key, label) => ({ key, label, type: 'checkbox' });
const list = (key, label) => ({ key, label, type: 'list', hint: 'Separate names with commas' });
const choice = (key, label, options) => ({ key, label, type: 'select', options });
const tri = (key, label) => ({ key, label, type: 'select', options: [['', 'Unchanged'], ['on', 'Yes'], ['off', 'No']], tri: true });

const schema = text('schema', 'Schema');
const name = text('name', 'Name');
const table = text('table', 'Table');
const cascadeBox = check('cascade', 'CASCADE (also drop objects that depend on it)');
const RELKINDS = [['table', 'Table'], ['view', 'View'], ['materialized_view', 'Materialised view'], ['sequence', 'Sequence']];

export const ACTIONS = [
  { id: 'create-schema', group: 'Schemas', label: 'Create schema', fields: [name, text('owner', 'Owner (optional)')], build: gen.createSchema },
  { id: 'rename-schema', group: 'Schemas', label: 'Rename schema', fields: [text('name', 'Schema'), text('newName', 'New name')], build: gen.renameSchema },
  { id: 'schema-owner', group: 'Schemas', label: 'Change schema owner', fields: [text('name', 'Schema'), text('owner', 'New owner')], build: gen.alterSchemaOwner },
  { id: 'drop-schema', group: 'Schemas', label: 'Drop schema', danger: true, fields: [text('name', 'Schema'), cascadeBox], build: gen.dropSchema },

  { id: 'create-table', group: 'Tables', label: 'Create table', fields: [schema, name, { key: 'columns', label: 'Columns', type: 'columns' }, check('unlogged', 'Unlogged'), area('comment', 'Comment (optional)')], build: gen.createTable },
  { id: 'rename-relation', group: 'Tables', label: 'Rename table, view or sequence', fields: [choice('kind', 'Kind', RELKINDS), schema, name, text('newName', 'New name')], build: gen.renameRelation },
  { id: 'comment', group: 'Tables', label: 'Set comment', fields: [choice('kind', 'Kind', RELKINDS), schema, name, area('comment', 'Comment (empty removes it)')], build: gen.setComment },
  { id: 'drop-relation', group: 'Tables', label: 'Drop table, view or sequence', danger: true, fields: [choice('kind', 'Kind', RELKINDS), schema, name, cascadeBox], build: gen.dropRelation },

  { id: 'add-column', group: 'Columns', label: 'Add column', fields: [schema, table, text('column.name', 'Column name'), text('column.type', 'Type', { placeholder: 'text, integer, numeric(10,2) …' }), check('column.notNull', 'NOT NULL'), text('column.default', 'Default (SQL expression, optional)')], build: gen.addColumn },
  { id: 'rename-column', group: 'Columns', label: 'Rename column', fields: [schema, table, text('column', 'Column'), text('newName', 'New name')], build: gen.renameColumn },
  { id: 'column-type', group: 'Columns', label: 'Change column type', fields: [schema, table, text('column', 'Column'), text('type', 'New type'), text('using', 'USING expression (optional)')], build: gen.alterColumnType },
  { id: 'column-null', group: 'Columns', label: 'Set or drop NOT NULL', fields: [schema, table, text('column', 'Column'), check('notNull', 'NOT NULL')], build: gen.setNotNull },
  { id: 'column-default', group: 'Columns', label: 'Set or drop default', fields: [schema, table, text('column', 'Column'), text('default', 'Default (SQL expression; empty drops it)')], build: gen.setDefault },
  { id: 'drop-column', group: 'Columns', label: 'Drop column', danger: true, fields: [schema, table, text('column', 'Column'), cascadeBox], build: gen.dropColumn },

  {
    id: 'add-constraint', group: 'Constraints and indexes', label: 'Add constraint',
    fields: [schema, table, choice('type', 'Kind', [['primary_key', 'Primary key'], ['unique', 'Unique'], ['check', 'Check'], ['foreign_key', 'Foreign key']]), text('name', 'Constraint name (optional)'),
      list('columns', 'Columns'), area('expression', 'Check condition (for Check)'), text('refSchema', 'Referenced schema (for Foreign key)'), text('refTable', 'Referenced table (for Foreign key)'),
      list('refColumns', 'Referenced columns (for Foreign key)'), choice('onDelete', 'On delete (for Foreign key)', [['no action', 'No action'], ['restrict', 'Restrict'], ['cascade', 'Cascade'], ['set null', 'Set null'], ['set default', 'Set default']])],
    build: gen.addConstraint,
  },
  { id: 'drop-constraint', group: 'Constraints and indexes', label: 'Drop constraint', danger: true, fields: [schema, table, text('name', 'Constraint name'), cascadeBox], build: gen.dropConstraint },
  { id: 'create-index', group: 'Constraints and indexes', label: 'Create index', fields: [schema, table, text('name', 'Index name (optional)'), list('columns', 'Columns'), choice('method', 'Method', [['btree', 'B-tree'], ['hash', 'Hash'], ['gin', 'GIN'], ['gist', 'GiST'], ['brin', 'BRIN'], ['spgist', 'SP-GiST']]), check('unique', 'Unique'), check('concurrently', 'Concurrently (doesn\'t block writes; not inside a transaction)')], build: gen.createIndex },
  { id: 'drop-index', group: 'Constraints and indexes', label: 'Drop index', danger: true, fields: [schema, text('name', 'Index'), check('concurrently', 'Concurrently'), cascadeBox], build: gen.dropIndex },

  { id: 'create-view', group: 'Views and sequences', label: 'Create view', fields: [schema, name, area('query', 'Query (SELECT …)'), check('orReplace', 'OR REPLACE'), check('materialized', 'Materialised')], build: gen.createView },
  { id: 'refresh-mv', group: 'Views and sequences', label: 'Refresh materialised view', fields: [schema, name, check('concurrently', 'Concurrently (needs a unique index)')], build: gen.refreshMaterializedView },
  { id: 'create-sequence', group: 'Views and sequences', label: 'Create sequence', fields: [schema, name, text('increment', 'Increment (optional)'), text('start', 'Start (optional)'), text('min', 'Minimum (optional)'), text('max', 'Maximum (optional)'), text('cache', 'Cache (optional)'), check('cycle', 'Cycle')], build: gen.createSequence },
  { id: 'restart-sequence', group: 'Views and sequences', label: 'Restart sequence', danger: true, fields: [schema, name, text('value', 'Restart with (optional)')], build: gen.restartSequence },

  { id: 'create-database', group: 'Databases', label: 'Create database', fields: [name, text('owner', 'Owner (optional)'), text('template', 'Template (optional)'), text('encoding', 'Encoding (optional)', { placeholder: 'UTF8' })], build: gen.createDatabase },
  { id: 'rename-database', group: 'Databases', label: 'Rename database', danger: true, fields: [text('name', 'Database'), text('newName', 'New name')], build: gen.renameDatabase },
  { id: 'database-owner', group: 'Databases', label: 'Change database owner', fields: [text('name', 'Database'), text('owner', 'New owner')], build: gen.alterDatabaseOwner },
  { id: 'drop-database', group: 'Databases', label: 'Drop database', danger: true, fields: [text('name', 'Database')], build: gen.dropDatabase },

  { id: 'create-role', group: 'Roles and privileges', label: 'Create role', fields: [name, check('login', 'Can log in (LOGIN)'), check('createdb', 'Can create databases'), check('createrole', 'Can create roles'), check('noinherit', 'Does not inherit privileges (NOINHERIT)'), text('connectionLimit', 'Connection limit (optional; -1 for none)'), list('inRoles', 'Member of (optional)'), area('comment', 'Comment (optional)')], build: (v) => gen.createRole({ ...v, inherit: v.noinherit ? false : undefined }) },
  { id: 'alter-role', group: 'Roles and privileges', label: 'Change role attributes', fields: [text('name', 'Role'), tri('login', 'Can log in'), tri('inherit', 'Inherits privileges'), tri('createdb', 'Can create databases'), tri('createrole', 'Can create roles'), text('connectionLimit', 'Connection limit (optional)')], build: gen.alterRole },
  { id: 'rename-role', group: 'Roles and privileges', label: 'Rename role', danger: true, fields: [text('name', 'Role'), text('newName', 'New name')], build: gen.renameRole },
  { id: 'grant-role', group: 'Roles and privileges', label: 'Grant role membership', fields: [text('role', 'Role'), text('member', 'To member'), check('admin', 'WITH ADMIN OPTION')], build: gen.grantRole },
  { id: 'revoke-role', group: 'Roles and privileges', label: 'Revoke role membership', danger: true, fields: [text('role', 'Role'), text('member', 'From member')], build: gen.revokeRole },
  { id: 'drop-role', group: 'Roles and privileges', label: 'Drop role', danger: true, fields: [text('name', 'Role')], build: gen.dropRole },
  { id: 'grant-table', group: 'Roles and privileges', label: 'Grant table privileges', fields: [schema, name, { key: 'privileges', label: 'Privileges', type: 'privileges' }, text('grantee', 'To role (or PUBLIC)'), check('grantOption', 'WITH GRANT OPTION')], build: gen.grantTable },
  { id: 'revoke-table', group: 'Roles and privileges', label: 'Revoke table privileges', danger: true, fields: [schema, name, { key: 'privileges', label: 'Privileges', type: 'privileges' }, text('grantee', 'From role (or PUBLIC)'), cascadeBox], build: gen.revokeTable },
  { id: 'grant-usage', group: 'Roles and privileges', label: 'Grant schema usage', fields: [text('name', 'Schema'), text('grantee', 'To role (or PUBLIC)')], build: gen.grantSchemaUsage },
];

const BY_ID = Object.fromEntries(ACTIONS.map((a) => [a.id, a]));
const GROUPS = [...new Set(ACTIONS.map((a) => a.group))];
const EMPTY_COLUMN = { name: '', type: '', notNull: false, default: '', primaryKey: false };

/** Form values (flat, with dotted keys for nested ones) to the generator's input. */
export function toInput(action, values, current) {
  const out = { current };
  for (const f of action.fields) {
    let v = values[f.key];
    if (f.type === 'list') v = gen.splitList(v);
    else if (f.tri) v = v === 'on' ? true : v === 'off' ? false : undefined;
    else if (f.type === 'checkbox') v = Boolean(v);
    else if (f.type === 'select' && v === undefined) v = f.options[0][0];
    else if (f.type === 'columns') v = v || [{ ...EMPTY_COLUMN }];
    else if (f.type === 'privileges') v = v || [];
    if (f.key.includes('.')) {
      const [outer, inner] = f.key.split('.');
      out[outer] = { ...(out[outer] || {}), [inner]: v };
    } else if (v !== undefined) {
      out[f.key] = v;
    }
  }
  return out;
}

function ColumnsEditor({ value, onChange }) {
  const rows = value && value.length ? value : [{ ...EMPTY_COLUMN }];
  const set = (i, k, v) => onChange(rows.map((r, j) => (j === i ? { ...r, [k]: v } : r)));
  return (
    <fieldset className="manage-columns">
      <legend>Columns</legend>
      {rows.map((r, i) => (
        <div className="manage-column" key={i}>
          <input aria-label={`Column ${i + 1} name`} value={r.name} onChange={(e) => set(i, 'name', e.target.value)} placeholder="name" />
          <input aria-label={`Column ${i + 1} type`} value={r.type} onChange={(e) => set(i, 'type', e.target.value)} placeholder="type" />
          <input aria-label={`Column ${i + 1} default`} value={r.default} onChange={(e) => set(i, 'default', e.target.value)} placeholder="default (optional)" />
          <label>
            <input type="checkbox" checked={r.notNull} onChange={(e) => set(i, 'notNull', e.target.checked)} /> NOT NULL
          </label>
          <label>
            <input type="checkbox" checked={r.primaryKey} onChange={(e) => set(i, 'primaryKey', e.target.checked)} /> Primary key
          </label>
          <button type="button" className="btn btn-link" aria-label={`Remove column ${i + 1}`} disabled={rows.length === 1} onClick={() => onChange(rows.filter((_, j) => j !== i))}>
            Remove
          </button>
        </div>
      ))}
      <button type="button" className="btn" onClick={() => onChange([...rows, { ...EMPTY_COLUMN }])}>
        Add column
      </button>
    </fieldset>
  );
}

function Field({ field, value, onChange, id }) {
  if (field.type === 'columns') return <ColumnsEditor value={value} onChange={onChange} />;
  if (field.type === 'privileges') {
    const chosen = value || [];
    return (
      <fieldset className="manage-privileges">
        <legend>{field.label}</legend>
        {gen.TABLE_PRIVILEGES.map((p) => (
          <label key={p}>
            <input type="checkbox" checked={chosen.includes(p)} onChange={(e) => onChange(e.target.checked ? [...chosen, p] : chosen.filter((x) => x !== p))} /> {p}
          </label>
        ))}
      </fieldset>
    );
  }
  if (field.type === 'checkbox') {
    return (
      <label className="manage-check">
        <input type="checkbox" checked={Boolean(value)} onChange={(e) => onChange(e.target.checked)} /> {field.label}
      </label>
    );
  }
  return (
    <div className="manage-field">
      <label htmlFor={id}>{field.label}</label>
      {field.type === 'textarea' ? (
        <textarea id={id} rows={3} value={value || ''} onChange={(e) => onChange(e.target.value)} />
      ) : field.type === 'select' ? (
        <select id={id} value={value ?? field.options[0][0]} onChange={(e) => onChange(e.target.value)}>
          {field.options.map(([v, l]) => (
            <option key={v} value={v}>
              {l}
            </option>
          ))}
        </select>
      ) : (
        <input id={id} value={value || ''} placeholder={field.placeholder} onChange={(e) => onChange(e.target.value)} aria-describedby={field.hint ? `${id}-hint` : undefined} />
      )}
      {field.hint ? (
        <span className="muted" id={`${id}-hint`}>
          {field.hint}
        </span>
      ) : null}
    </div>
  );
}

/**
 * `database`: the connected database (database actions refuse it); `onOpen(text, fileName)`: opens a new editor tab.
 */
export function ManagePanel({ database, onOpen }) {
  const baseId = useId();
  const [actionId, setActionId] = useState(ACTIONS[0].id);
  const [values, setValues] = useState({});
  const action = BY_ID[actionId];
  const generated = useMemo(() => {
    try {
      return { sql: action.build(toInput(action, values, database)) };
    } catch (e) {
      if (e instanceof gen.InputError) return { problem: e.message };
      throw e;
    }
  }, [action, values, database]);
  const choose = (id) => {
    setActionId(id);
    setValues({});
  };
  return (
    <section className="admin-panel manage-panel" aria-label="Manage">
      <div className="manage-form">
        <div className="manage-field">
          <label htmlFor={`${baseId}-action`}>What to do</label>
          <select id={`${baseId}-action`} value={actionId} onChange={(e) => choose(e.target.value)}>
            {GROUPS.map((g) => (
              <optgroup key={g} label={g}>
                {ACTIONS.filter((a) => a.group === g).map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.label}
                  </option>
                ))}
              </optgroup>
            ))}
          </select>
        </div>
        {action.fields.map((f) => (
          <Field key={f.key} field={f} id={`${baseId}-${f.key}`} value={values[f.key]} onChange={(v) => setValues((old) => ({ ...old, [f.key]: v }))} />
        ))}
      </div>
      <div className="manage-preview">
        <h3 className="admin-subtitle">SQL</h3>
        {generated.problem ? (
          <p className="muted" role="status">
            {generated.problem}
          </p>
        ) : (
          <pre className="manage-sql" aria-label="Generated SQL">
            {generated.sql}
          </pre>
        )}
        {action.danger && !generated.problem ? (
          <p className="result-truncated" role="note">
            This removes or changes something that may be hard to undo. When you run it, pgweb asks you to confirm.
          </p>
        ) : null}
        <button type="button" className="btn btn-primary" disabled={Boolean(generated.problem)} onClick={() => onOpen(generated.sql, `${action.id}.sql`)}>
          Open in editor
        </button>
        <p className="muted admin-note">
          Nothing runs from here. The SQL opens in a new editor tab; it runs only when you press Run or Run script, as your own role, with the
          same checks, confirmations and audit as any SQL you type.
        </p>
      </div>
    </section>
  );
}
