import { quoteIdent as q } from '../../api/metadata.js';

// Schema comparison (admin Phase 8, decision A13). Compares two snapshots from GET …/snapshot and writes the SQL that
// would make the target schema's objects like the source's. Nothing here runs anything: the SQL goes into an editor
// tab, and runs only if the user presses Run script, through the gate, confirmation, kill switch and audit, as the
// user's own role.
//
// The snapshots were read with search_path set to the compared schema alone, so names in that schema are written
// without it and everything else is qualified. The script sets search_path to the target schema for its transaction,
// so the same text creates the objects there.

/** Not compared: listed in the script's header and in the panel. */
export const NOT_COMPARED = [
  'types and domains',
  'comments',
  'owners and privileges',
  'row-level security policies',
  'sequence ownership (OWNED BY) and current values',
  'the contents of partitioned tables, partitions and foreign tables (reported, not generated)',
];

const RELKIND_LABEL = {
  table: 'table',
  partitioned_table: 'partitioned table',
  foreign_table: 'foreign table',
  view: 'view',
  materialized_view: 'materialised view',
  sequence: 'sequence',
};

/** Text that goes into an SQL comment: control characters (a line break would end the comment) become "?". */
export function commentText(s) {
  return Array.from(String(s), (ch) => {
    const c = ch.codePointAt(0);
    return c < 0x20 || c === 0x7f || c === 0x2028 || c === 0x2029 ? '?' : ch;
  }).join('');
}

const has = (v) => v !== null && v !== undefined;

function model(body) {
  const s = body.snapshot;
  const tables = new Map();
  const views = new Map();
  const sequences = new Map();
  const routines = new Map();
  for (const r of s.relations) {
    if (r.kind === 'view' || r.kind === 'materialized_view') views.set(r.name, { ...r, indexes: new Map() });
    else if (r.kind !== 'sequence') tables.set(r.name, { ...r, columns: new Map(), constraints: new Map(), indexes: new Map(), triggers: new Map() });
  }
  for (const x of s.sequences) sequences.set(x.name, x);
  for (const c of s.columns) tables.get(c.table)?.columns.set(c.name, c);
  for (const c of s.constraints) tables.get(c.table)?.constraints.set(c.name, c);
  for (const i of s.indexes) (tables.get(i.table) || views.get(i.table))?.indexes.set(i.name, i);
  for (const t of s.triggers) tables.get(t.table)?.triggers.set(t.name, t);
  for (const r of s.routines) routines.set(`${r.name}(${r.arguments})`, r);
  return { tables, views, sequences, routines };
}

/** A column as it appears in CREATE TABLE or ADD COLUMN. */
export function columnSql(c) {
  let s = `${q(c.name)} ${c.type}`;
  if (c.collation) s += ` COLLATE ${c.collation}`;
  if (c.generated) s += ` GENERATED ALWAYS AS (${c.default}) ${c.generated === 'v' ? 'VIRTUAL' : 'STORED'}`;
  else if (c.identity) s += ` GENERATED ${c.identity === 'a' ? 'ALWAYS' : 'BY DEFAULT'} AS IDENTITY`;
  else if (has(c.default)) s += ` DEFAULT ${c.default}`;
  if (c.notNull && !c.identity) s += ' NOT NULL';
  return s;
}

function sequenceOptions(x) {
  return `AS ${x.type} INCREMENT BY ${x.increment} MINVALUE ${x.min} MAXVALUE ${x.max} START WITH ${x.start} CACHE ${x.cache} ${x.cycle ? 'CYCLE' : 'NO CYCLE'}`;
}

function sameSequence(a, b) {
  return ['type', 'increment', 'min', 'max', 'start', 'cache', 'cycle'].every((k) => a[k] === b[k]);
}

const plainTable = (t) => t.kind === 'table' && !t.partition;

/**
 * Compares two snapshots (the bodies of GET …/snapshot). Returns the differences, for the panel, and the SQL that
 * would make the target like the source. Objects only in the target are dropped only with `includeDrops`; otherwise
 * their DROP statements are written as comments.
 */
export function compareSnapshots(sourceBody, targetBody, { includeDrops = false } = {}) {
  const src = model(sourceBody);
  const tgt = model(targetBody);
  const differences = [];
  // Drops come first, dependants before what they depend on: 0 triggers, constraints and indexes; 1 views;
  // 2 columns and tables; 3 routines and sequences.
  const sec = { drops: [[], [], [], []], sequences: [], tables: [], columns: [], routines: [], constraints: [], foreignKeys: [], views: [], indexes: [], triggers: [], review: [] };
  const diff = (kind, name, status, detail) => differences.push({ kind, name, status, detail });
  const drop = (level, sql) => sec.drops[level].push(sql);
  const gatedDrop = (level, sql) => {
    if (includeDrops) drop(level, sql);
    else if (/[\r\n]/.test(sql)) drop(level, '-- (a DROP statement is not shown here because the name contains a line break)');
    else drop(level, `-- ${sql}`);
  };
  const review = (text) => sec.review.push(`-- ${commentText(text)}`);

  // Sequences first: column defaults may use them.
  for (const [name, x] of src.sequences) {
    const t = tgt.sequences.get(name);
    if (!t) {
      diff('sequence', name, 'create', 'only in the source');
      sec.sequences.push(`CREATE SEQUENCE ${q(name)} ${sequenceOptions(x)};`);
    } else if (!sameSequence(x, t)) {
      diff('sequence', name, 'change', 'options differ');
      sec.sequences.push(`ALTER SEQUENCE ${q(name)} ${sequenceOptions(x)};`);
    }
  }
  for (const name of tgt.sequences.keys()) {
    if (!src.sequences.has(name)) {
      diff('sequence', name, 'drop', 'only in the target');
      gatedDrop(3, `DROP SEQUENCE ${q(name)};`);
    }
  }

  // Tables, their columns, constraints, indexes and triggers.
  for (const [name, s] of src.tables) {
    const t = tgt.tables.get(name);
    if (!plainTable(s) || (t && !plainTable(t))) {
      if (!t || s.kind !== t.kind || Boolean(s.partition) !== Boolean(t.partition) || JSON.stringify(columnsOf(s)) !== JSON.stringify(columnsOf(t))) {
        diff(RELKIND_LABEL[s.kind], name, 'review', t ? 'differs; not generated' : 'only in the source; not generated');
        review(`Not generated: ${q(name)} is a ${s.partition ? 'partition' : RELKIND_LABEL[s.kind]}${t ? ` in the source and a ${t.partition ? 'partition' : RELKIND_LABEL[t.kind]} in the target` : ''}; write its SQL by hand.`);
      }
      continue;
    }
    if (!t) {
      diff('table', name, 'create', 'only in the source');
      const cols = [...s.columns.values()].map((c) => `    ${columnSql(c)}`);
      sec.tables.push(`CREATE ${s.unlogged ? 'UNLOGGED ' : ''}TABLE ${q(name)} (\n${cols.join(',\n')}\n);`);
      for (const c of s.constraints.values()) addConstraint(sec, name, c);
      for (const i of s.indexes.values()) sec.indexes.push(`${i.definition};`);
      for (const tr of s.triggers.values()) sec.triggers.push(`${tr.definition};`);
      continue;
    }
    if (Boolean(s.unlogged) !== Boolean(t.unlogged)) {
      diff('table', name, 'change', s.unlogged ? 'unlogged in the source' : 'logged in the source');
      sec.columns.push(`ALTER TABLE ${q(name)} SET ${s.unlogged ? 'UNLOGGED' : 'LOGGED'};`);
    }
    compareColumns(name, s, t, sec, diff, gatedDrop, review);
    for (const [cname, c] of s.constraints) {
      const tc = t.constraints.get(cname);
      if (!tc) {
        diff('constraint', `${name}.${cname}`, 'create', 'only in the source');
        addConstraint(sec, name, c);
      } else if (tc.definition !== c.definition || tc.type !== c.type) {
        diff('constraint', `${name}.${cname}`, 'change', `${tc.definition} → ${c.definition}`);
        drop(0, `ALTER TABLE ${q(name)} DROP CONSTRAINT ${q(cname)};`);
        addConstraint(sec, name, c);
      }
    }
    for (const cname of t.constraints.keys()) {
      if (!s.constraints.has(cname)) {
        diff('constraint', `${name}.${cname}`, 'drop', 'only in the target');
        gatedDrop(0, `ALTER TABLE ${q(name)} DROP CONSTRAINT ${q(cname)};`);
      }
    }
    compareIndexes(name, s, t, sec, diff, gatedDrop);
    for (const [trname, tr] of s.triggers) {
      const tt = t.triggers.get(trname);
      if (!tt) {
        diff('trigger', `${name}.${trname}`, 'create', 'only in the source');
        sec.triggers.push(`${tr.definition};`);
      } else if (tt.definition !== tr.definition) {
        diff('trigger', `${name}.${trname}`, 'change', 'definition differs');
        drop(0, `DROP TRIGGER ${q(trname)} ON ${q(name)};`);
        sec.triggers.push(`${tr.definition};`);
      }
    }
    for (const trname of t.triggers.keys()) {
      if (!s.triggers.has(trname)) {
        diff('trigger', `${name}.${trname}`, 'drop', 'only in the target');
        gatedDrop(0, `DROP TRIGGER ${q(trname)} ON ${q(name)};`);
      }
    }
  }
  for (const [name, t] of tgt.tables) {
    if (!src.tables.has(name)) {
      diff(RELKIND_LABEL[t.kind], name, 'drop', 'only in the target');
      gatedDrop(2, `DROP ${t.kind === 'foreign_table' ? 'FOREIGN TABLE' : 'TABLE'} ${q(name)};`);
    }
  }

  // Functions and procedures, after the tables their bodies may use.
  for (const [key, r] of src.routines) {
    const t = tgt.routines.get(key);
    if (!t) {
      diff(r.kind, key, 'create', 'only in the source');
      sec.routines.push(`${r.definition};`);
    } else if (t.definition !== r.definition) {
      diff(r.kind, key, 'change', 'definition differs');
      if (t.kind !== r.kind) drop(3, `DROP ${t.kind.toUpperCase()} ${q(t.name)}(${t.arguments});`);
      sec.routines.push(`${r.definition};`);
    }
  }
  for (const [key, t] of tgt.routines) {
    if (!src.routines.has(key)) {
      diff(t.kind, key, 'drop', 'only in the target');
      gatedDrop(3, `DROP ${t.kind.toUpperCase()} ${q(t.name)}(${t.arguments});`);
    }
  }

  // Views and materialised views, then their indexes.
  for (const [name, v] of src.views) {
    const t = tgt.views.get(name);
    const mat = v.kind === 'materialized_view';
    const create = `CREATE ${mat ? 'MATERIALIZED VIEW' : 'VIEW'} ${q(name)} AS\n${v.definition};`;
    if (!t) {
      diff(RELKIND_LABEL[v.kind], name, 'create', 'only in the source');
      sec.views.push(create);
      for (const i of v.indexes.values()) sec.indexes.push(`${i.definition};`);
      continue;
    }
    if (t.kind !== v.kind || t.definition !== v.definition) {
      diff(RELKIND_LABEL[v.kind], name, 'change', t.kind !== v.kind ? `a ${RELKIND_LABEL[t.kind]} in the target` : 'query differs');
      if (!mat && t.kind === 'view') {
        sec.views.push(`CREATE OR REPLACE VIEW ${q(name)} AS\n${v.definition};`);
      } else {
        // A materialised view can't be replaced: it is dropped and created again (its rows are computed again).
        drop(1, `DROP ${t.kind === 'materialized_view' ? 'MATERIALIZED VIEW' : 'VIEW'} ${q(name)};`);
        sec.views.push(create);
        for (const i of v.indexes.values()) sec.indexes.push(`${i.definition};`);
        continue;
      }
    }
    compareIndexes(name, v, t, sec, diff, gatedDrop);
  }
  for (const [name, t] of tgt.views) {
    if (!src.views.has(name)) {
      diff(RELKIND_LABEL[t.kind], name, 'drop', 'only in the target');
      gatedDrop(1, `DROP ${t.kind === 'materialized_view' ? 'MATERIALIZED VIEW' : 'VIEW'} ${q(name)};`);
    }
  }

  const order = ['sequences', 'tables', 'columns', 'routines', 'constraints', 'foreignKeys', 'views', 'indexes', 'triggers'];
  const body = [...sec.drops.flat(), ...order.flatMap((k) => sec[k])];
  return { differences, sql: script(sourceBody, targetBody, body, sec.review, includeDrops) };
}

function columnsOf(t) {
  return t ? [...t.columns.values()] : null;
}

function addConstraint(sec, table, c) {
  (c.type === 'foreign_key' ? sec.foreignKeys : sec.constraints).push(`ALTER TABLE ${q(table)} ADD CONSTRAINT ${q(c.name)} ${c.definition};`);
}

function compareColumns(table, s, t, sec, diff, gatedDrop, review) {
  const alter = (sql) => sec.columns.push(`ALTER TABLE ${q(table)} ${sql};`);
  for (const [name, c] of s.columns) {
    const tc = t.columns.get(name);
    const label = `${table}.${name}`;
    if (!tc) {
      diff('column', label, 'create', 'only in the source');
      alter(`ADD COLUMN ${columnSql(c)}`);
      continue;
    }
    if ((c.identity || null) !== (tc.identity || null) || (c.generated || null) !== (tc.generated || null) || (c.generated && c.default !== tc.default)) {
      diff('column', label, 'review', 'identity or generation differs; not generated');
      review(`Not generated: column ${q(table)}.${q(name)} is an identity or generated column in a different way in the source; change it by hand.`);
      continue;
    }
    if (c.type !== tc.type || (c.collation || null) !== (tc.collation || null)) {
      diff('column', label, 'change', c.type !== tc.type ? `type ${tc.type} → ${c.type}` : 'collation differs');
      alter(`ALTER COLUMN ${q(name)} TYPE ${c.type}${c.collation ? ` COLLATE ${c.collation}` : ''}`);
    }
    if (!c.identity && !c.generated && (c.default ?? null) !== (tc.default ?? null)) {
      diff('column', label, 'change', has(c.default) ? `default ${c.default}` : 'no default in the source');
      alter(!has(c.default) ? `ALTER COLUMN ${q(name)} DROP DEFAULT` : `ALTER COLUMN ${q(name)} SET DEFAULT ${c.default}`);
    }
    if (!c.identity && c.notNull !== tc.notNull) {
      diff('column', label, 'change', c.notNull ? 'NOT NULL in the source' : 'nullable in the source');
      alter(`ALTER COLUMN ${q(name)} ${c.notNull ? 'SET' : 'DROP'} NOT NULL`);
    }
  }
  for (const name of t.columns.keys()) {
    if (!s.columns.has(name)) {
      diff('column', `${table}.${name}`, 'drop', 'only in the target');
      gatedDrop(2, `ALTER TABLE ${q(table)} DROP COLUMN ${q(name)};`);
    }
  }
}

function compareIndexes(table, s, t, sec, diff, gatedDrop) {
  for (const [name, i] of s.indexes) {
    const ti = t.indexes.get(name);
    if (!ti) {
      diff('index', `${table}.${name}`, 'create', 'only in the source');
      sec.indexes.push(`${i.definition};`);
    } else if (ti.definition !== i.definition) {
      diff('index', `${table}.${name}`, 'change', 'definition differs');
      sec.drops[0].push(`DROP INDEX ${q(name)};`);
      sec.indexes.push(`${i.definition};`);
    }
  }
  for (const name of t.indexes.keys()) {
    if (!s.indexes.has(name)) {
      diff('index', `${table}.${name}`, 'drop', 'only in the target');
      gatedDrop(0, `DROP INDEX ${q(name)};`);
    }
  }
}

function where(body) {
  return commentText(`${q(body.database)}, schema ${q(body.schema)}, read ${body.loadedAt}`);
}

function script(source, target, body, review, includeDrops) {
  const head = [
    '-- Schema comparison generated by pgweb. Nothing has been run.',
    `-- Source: ${where(source)}`,
    `-- Target: ${where(target)}`,
    "-- Running this script with Run script makes the target's compared objects like the source's, as your own role.",
    '-- Read every statement first. It is one transaction: if a statement fails, roll back and nothing is changed.',
    includeDrops
      ? '-- It DROPS objects and columns that exist only in the target: their data is lost when it is committed.'
      : '-- Objects and columns that exist only in the target are not dropped: their DROP statements are comments.',
    `-- Not compared: ${NOT_COMPARED.join('; ')}.`,
  ];
  if (body.length === 0) {
    return [...head, '', '-- No differences that can be written as SQL.', ...review, ''].join('\n');
  }
  return [...head, ...(review.length ? ['', ...review] : []), '', 'BEGIN;', `SET LOCAL search_path TO ${q(target.schema)};`, '', ...body, '', 'COMMIT;', ''].join('\n');
}
