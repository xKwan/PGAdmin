import { qualified, quoteIdent } from '../../api/metadata.js';
import { quoteLiteral } from '../manage/sqlGen.js';

// Row editing for the table data browser (admin Phase 6, decision A2). Builds the statements for one save; nothing
// here runs anything. Rows are addressed by their primary key only, and each UPDATE also checks that the columns it
// changes still hold what was loaded (compared as text, the same text the browser received), so a row someone else
// changed in the meantime is not overwritten: the statement then affects 0 rows and the save is rolled back.

const lit = (v) => (v === null || v === undefined ? 'NULL' : quoteLiteral(v));

/** Columns a row edit may set: not generated, not GENERATED ALWAYS AS IDENTITY. */
export function isEditable(column) {
  return !column.generated && column.identity !== 'a';
}

/** The browse query: every column, by primary key (or the first columns), one page plus one row to know there's more. */
export function browseSql(schema, table, columns, primaryKey, limit, offset) {
  const order = (primaryKey.length ? primaryKey : columns.slice(0, 1).map((c) => c.name)).map(quoteIdent).join(', ');
  const cols = columns.map((c) => quoteIdent(c.name)).join(', ');
  return `SELECT ${cols}\nFROM ${qualified(schema, table)}${order ? `\nORDER BY ${order}` : ''}\nLIMIT ${limit + 1} OFFSET ${offset}`;
}

function keyCondition(primaryKey, row, index) {
  return primaryKey.map((k) => `${quoteIdent(k)} = ${lit(row[index[k]])}`).join(' AND ');
}

/** A short description of a row by its key, for messages: `id = 5`. */
export function describeKey(primaryKey, row, index) {
  return primaryKey.map((k) => `${k} = ${row[index[k]]}`).join(', ');
}

/**
 * The statements of one save. `columns`: [{name}] in the order of `rows`' values; `changes`: {rowIndex: {column:
 * value or null}}; `deleted`: row indexes; `inserted`: [{column: value or null}] (a missing column takes its
 * default). Returns [{sql, kind, row}] with deletes first, then updates, then inserts, so a key freed by a delete can
 * be reused by an insert of the same save.
 */
export function buildSave({ schema, table, columns, primaryKey, rows, changes = {}, deleted = [], inserted = [] }) {
  if (!primaryKey || primaryKey.length === 0) throw new Error('Rows can only be changed in a table with a primary key.');
  const target = qualified(schema, table);
  const index = Object.fromEntries(columns.map((c, i) => [c.name, i]));
  for (const k of primaryKey) {
    if (!(k in index)) throw new Error(`The primary key column ${k} is not among the loaded columns.`);
  }
  const out = [];
  const gone = new Set(deleted);
  for (const r of [...gone].sort((a, b) => a - b)) {
    out.push({ kind: 'delete', row: r, sql: `DELETE FROM ${target} WHERE ${keyCondition(primaryKey, rows[r], index)};` });
  }
  for (const r of Object.keys(changes).map(Number).sort((a, b) => a - b)) {
    if (gone.has(r)) continue;
    const set = Object.entries(changes[r]).filter(([c, v]) => v !== rows[r][index[c]]);
    if (set.length === 0) continue;
    const assignments = set.map(([c, v]) => `${quoteIdent(c)} = ${lit(v)}`).join(', ');
    const unchanged = set.map(([c]) => `${quoteIdent(c)}::text IS NOT DISTINCT FROM ${lit(rows[r][index[c]])}`).join(' AND ');
    out.push({ kind: 'update', row: r, sql: `UPDATE ${target} SET ${assignments}\nWHERE ${keyCondition(primaryKey, rows[r], index)} AND ${unchanged};` });
  }
  inserted.forEach((values, i) => {
    const set = Object.entries(values).filter(([, v]) => v !== undefined);
    const sql = set.length
      ? `INSERT INTO ${target} (${set.map(([c]) => quoteIdent(c)).join(', ')})\nVALUES (${set.map(([, v]) => lit(v)).join(', ')});`
      : `INSERT INTO ${target} DEFAULT VALUES;`;
    out.push({ kind: 'insert', row: i, sql });
  });
  return out;
}
