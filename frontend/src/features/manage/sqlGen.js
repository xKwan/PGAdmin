import { qualified, quoteIdent } from '../../api/metadata.js';

// SQL generation for the management screens (admin Phase 5, decision A1). Every function returns SQL text for the
// editor; nothing here runs anything. The text runs only when the user presses Run or Run script, through the same
// gate, confirmation, kill switch, limits and audit as typed SQL, as the user's own role. Names are always quoted with
// quoteIdent; values with quoteLiteral. Types, defaults, check conditions and view queries are SQL the user writes and
// reviews, so they are passed through (trimmed, without a trailing semicolon) after a basic shape check.

/** A problem with what was typed; the message is shown next to the form. */
export class InputError extends Error {}

const encoder = new TextEncoder();

function present(v) {
  return v !== undefined && v !== null && String(v).trim() !== '';
}

/** A PostgreSQL name: required, at most 63 bytes, no NUL. Kept exactly as typed (quoted when needed). */
export function checkName(v, what) {
  if (!present(v)) throw new InputError(`${what} is required.`);
  const s = String(v);
  if (s.includes('\0')) throw new InputError(`${what} can't contain a NUL character.`);
  if (encoder.encode(s).length > 63) throw new InputError(`${what} is longer than 63 bytes.`);
  return s;
}

/** A string literal; E'' form when it contains a backslash, so it means the same whatever standard_conforming_strings is. */
export function quoteLiteral(v) {
  const s = String(v);
  if (s.includes('\0')) throw new InputError("Text can't contain a NUL character.");
  const body = s.replaceAll("'", "''");
  return s.includes('\\') ? `E'${body.replaceAll('\\', '\\\\')}'` : `'${body}'`;
}

const TYPE = /^[A-Za-z_][A-Za-z0-9_$ ."(),[\]]*$/;

/** A type name such as `integer`, `numeric(10,2)`, `varchar(80)[]` or `public."My type"`. */
export function checkType(v, what = 'Type') {
  if (!present(v)) throw new InputError(`${what} is required.`);
  const t = String(v).trim();
  if (!TYPE.test(t) || t.includes('--')) throw new InputError(`${what} doesn't look like a type name.`);
  return t;
}

/** An SQL expression or query the user wrote: trimmed, trailing semicolons removed, required unless optional. */
export function checkExpression(v, what, optional = false) {
  if (!present(v)) {
    if (optional) return null;
    throw new InputError(`${what} is required.`);
  }
  const s = String(v).trim().replace(/;+\s*$/, '').trim();
  if (s.includes('\0')) throw new InputError(`${what} can't contain a NUL character.`);
  if (s === '') throw new InputError(`${what} is required.`);
  return s;
}

function int(v, what, { min = -(2 ** 63), optional = true } = {}) {
  if (!present(v)) {
    if (optional) return null;
    throw new InputError(`${what} is required.`);
  }
  const s = String(v).trim();
  if (!/^-?\d{1,19}$/.test(s) || Number(s) < min) throw new InputError(`${what} must be a whole number${min === 0 ? ' of 0 or more' : ''}.`);
  return s;
}

function names(list, what) {
  const out = (list || []).map((n) => (typeof n === 'string' ? n.trim() : n)).filter(present);
  if (out.length === 0) throw new InputError(`${what}: at least one is required.`);
  return out.map((n) => quoteIdent(checkName(n, what)));
}

/** Splits "a, b, c" into names (names with commas must be typed in the per-row editors instead). */
export function splitList(text) {
  return String(text || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

const rel = (o) => qualified(checkName(o.schema, 'Schema'), checkName(o.name, 'Name'));
const tbl = (o) => qualified(checkName(o.schema, 'Schema'), checkName(o.table, 'Table'));
const cascade = (o) => (o.cascade ? ' CASCADE' : '');
const end = (s) => `${s};\n`;

// ---------------------------------------------------------------- schemas

export function createSchema(o) {
  const owner = present(o.owner) ? ` AUTHORIZATION ${quoteIdent(checkName(o.owner, 'Owner'))}` : '';
  return end(`CREATE SCHEMA ${quoteIdent(checkName(o.name, 'Name'))}${owner}`);
}

export function renameSchema(o) {
  return end(`ALTER SCHEMA ${quoteIdent(checkName(o.name, 'Schema'))} RENAME TO ${quoteIdent(checkName(o.newName, 'New name'))}`);
}

export function alterSchemaOwner(o) {
  return end(`ALTER SCHEMA ${quoteIdent(checkName(o.name, 'Schema'))} OWNER TO ${quoteIdent(checkName(o.owner, 'Owner'))}`);
}

export function dropSchema(o) {
  return end(`DROP SCHEMA ${quoteIdent(checkName(o.name, 'Schema'))}${cascade(o)}`);
}

// ---------------------------------------------------------------- tables and columns

function columnDefinition(c) {
  let s = `${quoteIdent(checkName(c.name, 'Column name'))} ${checkType(c.type, `Type of ${c.name}`)}`;
  if (c.notNull) s += ' NOT NULL';
  const d = checkExpression(c.default, `Default of ${c.name}`, true);
  if (d !== null) s += ` DEFAULT ${d}`;
  return s;
}

export function createTable(o) {
  const columns = (o.columns || []).filter((c) => present(c.name) || present(c.type));
  if (columns.length === 0) throw new InputError('Columns: at least one is required.');
  const lines = columns.map(columnDefinition);
  const pk = columns.filter((c) => c.primaryKey).map((c) => quoteIdent(c.name));
  if (pk.length) lines.push(`PRIMARY KEY (${pk.join(', ')})`);
  let sql = end(`CREATE ${o.unlogged ? 'UNLOGGED ' : ''}TABLE ${rel(o)} (\n    ${lines.join(',\n    ')}\n)`);
  if (present(o.comment)) sql += end(`COMMENT ON TABLE ${rel(o)} IS ${quoteLiteral(o.comment)}`);
  return sql;
}

export function renameRelation(o) {
  const kind = { table: 'TABLE', view: 'VIEW', materialized_view: 'MATERIALIZED VIEW', sequence: 'SEQUENCE' }[o.kind || 'table'];
  if (!kind) throw new InputError('Unknown kind of object.');
  return end(`ALTER ${kind} ${rel(o)} RENAME TO ${quoteIdent(checkName(o.newName, 'New name'))}`);
}

export function setComment(o) {
  const kind = { table: 'TABLE', view: 'VIEW', materialized_view: 'MATERIALIZED VIEW', sequence: 'SEQUENCE' }[o.kind || 'table'];
  if (!kind) throw new InputError('Unknown kind of object.');
  return end(`COMMENT ON ${kind} ${rel(o)} IS ${present(o.comment) ? quoteLiteral(o.comment) : 'NULL'}`);
}

export function dropRelation(o) {
  const kind = { table: 'TABLE', view: 'VIEW', materialized_view: 'MATERIALIZED VIEW', sequence: 'SEQUENCE' }[o.kind || 'table'];
  if (!kind) throw new InputError('Unknown kind of object.');
  return end(`DROP ${kind} ${rel(o)}${cascade(o)}`);
}

export function addColumn(o) {
  return end(`ALTER TABLE ${tbl(o)} ADD COLUMN ${columnDefinition(o.column || {})}`);
}

export function renameColumn(o) {
  return end(`ALTER TABLE ${tbl(o)} RENAME COLUMN ${quoteIdent(checkName(o.column, 'Column'))} TO ${quoteIdent(checkName(o.newName, 'New name'))}`);
}

export function alterColumnType(o) {
  const using = checkExpression(o.using, 'USING expression', true);
  return end(`ALTER TABLE ${tbl(o)} ALTER COLUMN ${quoteIdent(checkName(o.column, 'Column'))} TYPE ${checkType(o.type)}${using ? ` USING ${using}` : ''}`);
}

export function setNotNull(o) {
  return end(`ALTER TABLE ${tbl(o)} ALTER COLUMN ${quoteIdent(checkName(o.column, 'Column'))} ${o.notNull ? 'SET' : 'DROP'} NOT NULL`);
}

export function setDefault(o) {
  const d = checkExpression(o.default, 'Default', true);
  return end(`ALTER TABLE ${tbl(o)} ALTER COLUMN ${quoteIdent(checkName(o.column, 'Column'))} ${d === null ? 'DROP DEFAULT' : `SET DEFAULT ${d}`}`);
}

export function dropColumn(o) {
  return end(`ALTER TABLE ${tbl(o)} DROP COLUMN ${quoteIdent(checkName(o.column, 'Column'))}${cascade(o)}`);
}

// ---------------------------------------------------------------- constraints and indexes

const FK_ACTIONS = { 'no action': 'NO ACTION', restrict: 'RESTRICT', cascade: 'CASCADE', 'set null': 'SET NULL', 'set default': 'SET DEFAULT' };

export function addConstraint(o) {
  const name = present(o.name) ? `CONSTRAINT ${quoteIdent(checkName(o.name, 'Constraint name'))} ` : '';
  let body;
  switch (o.type) {
    case 'primary_key':
      body = `PRIMARY KEY (${names(o.columns, 'Columns').join(', ')})`;
      break;
    case 'unique':
      body = `UNIQUE (${names(o.columns, 'Columns').join(', ')})`;
      break;
    case 'check':
      body = `CHECK (${checkExpression(o.expression, 'Condition')})`;
      break;
    case 'foreign_key': {
      const cols = names(o.columns, 'Columns');
      const refs = names(o.refColumns, 'Referenced columns');
      if (cols.length !== refs.length) throw new InputError('Columns and referenced columns must have the same number of names.');
      const ref = qualified(checkName(o.refSchema, 'Referenced schema'), checkName(o.refTable, 'Referenced table'));
      const onDelete = FK_ACTIONS[o.onDelete || 'no action'];
      if (!onDelete) throw new InputError('Unknown ON DELETE action.');
      body = `FOREIGN KEY (${cols.join(', ')}) REFERENCES ${ref} (${refs.join(', ')})${onDelete === 'NO ACTION' ? '' : ` ON DELETE ${onDelete}`}`;
      break;
    }
    default:
      throw new InputError('Choose a kind of constraint.');
  }
  return end(`ALTER TABLE ${tbl(o)} ADD ${name}${body}`);
}

export function dropConstraint(o) {
  return end(`ALTER TABLE ${tbl(o)} DROP CONSTRAINT ${quoteIdent(checkName(o.name, 'Constraint name'))}${cascade(o)}`);
}

const METHODS = new Set(['btree', 'hash', 'gin', 'gist', 'brin', 'spgist']);

export function createIndex(o) {
  const method = o.method || 'btree';
  if (!METHODS.has(method)) throw new InputError('Unknown index method.');
  const name = present(o.name) ? `${quoteIdent(checkName(o.name, 'Index name'))} ` : '';
  const using = method === 'btree' ? '' : ` USING ${method}`;
  return end(`CREATE ${o.unique ? 'UNIQUE ' : ''}INDEX ${o.concurrently ? 'CONCURRENTLY ' : ''}${name}ON ${tbl(o)}${using} (${names(o.columns, 'Columns').join(', ')})`);
}

export function dropIndex(o) {
  return end(`DROP INDEX ${o.concurrently ? 'CONCURRENTLY ' : ''}${rel(o)}${o.concurrently ? '' : cascade(o)}`);
}

// ---------------------------------------------------------------- views and sequences

export function createView(o) {
  const query = checkExpression(o.query, 'Query');
  if (o.materialized) return end(`CREATE MATERIALIZED VIEW ${rel(o)} AS\n${query}`);
  return end(`CREATE ${o.orReplace ? 'OR REPLACE ' : ''}VIEW ${rel(o)} AS\n${query}`);
}

export function refreshMaterializedView(o) {
  return end(`REFRESH MATERIALIZED VIEW ${o.concurrently ? 'CONCURRENTLY ' : ''}${rel(o)}`);
}

export function createSequence(o) {
  const parts = [];
  const inc = int(o.increment, 'Increment');
  if (inc === '0') throw new InputError("Increment can't be 0.");
  if (inc !== null) parts.push(`INCREMENT BY ${inc}`);
  const min = int(o.min, 'Minimum');
  if (min !== null) parts.push(`MINVALUE ${min}`);
  const max = int(o.max, 'Maximum');
  if (max !== null) parts.push(`MAXVALUE ${max}`);
  const start = int(o.start, 'Start');
  if (start !== null) parts.push(`START WITH ${start}`);
  const cache = int(o.cache, 'Cache', { min: 1 });
  if (cache !== null) parts.push(`CACHE ${cache}`);
  if (o.cycle) parts.push('CYCLE');
  return end(`CREATE SEQUENCE ${rel(o)}${parts.length ? `\n    ${parts.join('\n    ')}` : ''}`);
}

export function restartSequence(o) {
  const v = int(o.value, 'Restart value');
  return end(`ALTER SEQUENCE ${rel(o)} RESTART${v === null ? '' : ` WITH ${v}`}`);
}

// ---------------------------------------------------------------- databases (decision A3: never the connected one)

function otherDatabase(name, current) {
  const n = checkName(name, 'Database');
  if (n === current) throw new InputError('That is the database you are connected to: switch to another database first.');
  return quoteIdent(n);
}

export function createDatabase(o) {
  const parts = [];
  if (present(o.owner)) parts.push(`OWNER ${quoteIdent(checkName(o.owner, 'Owner'))}`);
  if (present(o.template)) parts.push(`TEMPLATE ${quoteIdent(checkName(o.template, 'Template'))}`);
  if (present(o.encoding)) {
    if (!/^[A-Za-z0-9_]{1,30}$/.test(o.encoding)) throw new InputError("Encoding doesn't look like an encoding name.");
    parts.push(`ENCODING ${quoteLiteral(o.encoding)}`);
  }
  return end(`CREATE DATABASE ${quoteIdent(checkName(o.name, 'Name'))}${parts.length ? ` ${parts.join(' ')}` : ''}`);
}

export function renameDatabase(o) {
  return end(`ALTER DATABASE ${otherDatabase(o.name, o.current)} RENAME TO ${quoteIdent(checkName(o.newName, 'New name'))}`);
}

export function alterDatabaseOwner(o) {
  return end(`ALTER DATABASE ${quoteIdent(checkName(o.name, 'Database'))} OWNER TO ${quoteIdent(checkName(o.owner, 'Owner'))}`);
}

/** Without FORCE: PostgreSQL refuses while anyone is connected, so nobody's session is ended by this. */
export function dropDatabase(o) {
  return end(`DROP DATABASE ${otherDatabase(o.name, o.current)}`);
}

// ---------------------------------------------------------------- roles (decision A8: no passwords, no superuser)

const ROLE_FLAGS = [
  ['login', 'LOGIN', 'NOLOGIN'],
  ['inherit', 'INHERIT', 'NOINHERIT'],
  ['createdb', 'CREATEDB', 'NOCREATEDB'],
  ['createrole', 'CREATEROLE', 'NOCREATEROLE'],
];

/** true → the attribute, false → its NO… form, undefined → not mentioned. */
function roleOptions(o) {
  const parts = [];
  for (const [key, on, off] of ROLE_FLAGS) {
    if (o[key] === true) parts.push(on);
    else if (o[key] === false) parts.push(off);
  }
  const limit = int(o.connectionLimit, 'Connection limit', { min: -1 });
  if (limit !== null) parts.push(`CONNECTION LIMIT ${limit}`);
  return parts;
}

/** Only what differs from CREATE ROLE's defaults (NOLOGIN, INHERIT, NOCREATEDB, NOCREATEROLE) is written. */
export function createRole(o) {
  const parts = roleOptions({
    login: o.login ? true : undefined,
    inherit: o.inherit === false ? false : undefined,
    createdb: o.createdb ? true : undefined,
    createrole: o.createrole ? true : undefined,
    connectionLimit: o.connectionLimit,
  });
  const inRoles = (o.inRoles || []).filter(present);
  if (inRoles.length) parts.push(`IN ROLE ${names(inRoles, 'Member of').join(', ')}`);
  let sql = end(`CREATE ROLE ${quoteIdent(checkName(o.name, 'Name'))}${parts.length ? ` ${parts.join(' ')}` : ''}`);
  if (present(o.comment)) sql += end(`COMMENT ON ROLE ${quoteIdent(o.name)} IS ${quoteLiteral(o.comment)}`);
  return sql;
}

export function alterRole(o) {
  const parts = roleOptions(o);
  if (parts.length === 0) throw new InputError('Choose at least one change.');
  return end(`ALTER ROLE ${quoteIdent(checkName(o.name, 'Role'))} ${parts.join(' ')}`);
}

export function renameRole(o) {
  return end(`ALTER ROLE ${quoteIdent(checkName(o.name, 'Role'))} RENAME TO ${quoteIdent(checkName(o.newName, 'New name'))}`);
}

export function grantRole(o) {
  const opts = [];
  if (o.admin) opts.push('ADMIN OPTION');
  return end(`GRANT ${quoteIdent(checkName(o.role, 'Role'))} TO ${quoteIdent(checkName(o.member, 'Member'))}${opts.length ? ` WITH ${opts.join(', ')}` : ''}`);
}

export function revokeRole(o) {
  return end(`REVOKE ${quoteIdent(checkName(o.role, 'Role'))} FROM ${quoteIdent(checkName(o.member, 'Member'))}`);
}

export function dropRole(o) {
  return end(`DROP ROLE ${quoteIdent(checkName(o.name, 'Role'))}`);
}

// ---------------------------------------------------------------- privileges

const TABLE_PRIVILEGES = ['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER', 'MAINTAIN'];

function grantee(v) {
  return String(v).trim().toUpperCase() === 'PUBLIC' ? 'PUBLIC' : quoteIdent(checkName(v, 'Grantee'));
}

function privileges(list) {
  const p = (list || []).filter((x) => TABLE_PRIVILEGES.includes(x));
  if (p.length === 0) throw new InputError('Choose at least one privilege.');
  return p.join(', ');
}

export function grantTable(o) {
  return end(`GRANT ${privileges(o.privileges)} ON TABLE ${rel(o)} TO ${grantee(o.grantee)}${o.grantOption ? ' WITH GRANT OPTION' : ''}`);
}

export function revokeTable(o) {
  return end(`REVOKE ${privileges(o.privileges)} ON TABLE ${rel(o)} FROM ${grantee(o.grantee)}${cascade(o)}`);
}

export function grantSchemaUsage(o) {
  return end(`GRANT USAGE ON SCHEMA ${quoteIdent(checkName(o.name, 'Schema'))} TO ${grantee(o.grantee)}`);
}

export { TABLE_PRIVILEGES };
