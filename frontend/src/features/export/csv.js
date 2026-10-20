import { qualified, quoteIdent } from '../../api/metadata.js';
import { quoteLiteral } from '../manage/sqlGen.js';

// Export and import (admin Phase 7, decisions A4 and A5). Export writes only rows the browser already holds; import
// turns a CSV file into INSERT statements for the editor. Nothing here talks to the server.

export const IMPORT_MAX_BYTES = 1024 * 1024;
export const IMPORT_MAX_ROWS = 5000;
export const SCRIPT_MAX_BYTES = 900 * 1024; // below the server's 1 MiB text limit
const ROWS_PER_INSERT = 100;
const NUMBER = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/;

/** A cell for CSV: quoted when needed; text that a spreadsheet would run as a formula gets a leading '. */
export function csvCell(v) {
  if (v === null || v === undefined) return '';
  let s = String(v);
  if (/^[=+\-@\t\r]/.test(s) && !NUMBER.test(s)) s = `'${s}`;
  return /[",\r\n]/.test(s) || s === '' ? `"${s.replaceAll('"', '""')}"` : s;
}

/**
 * CSV with a header row, CRLF line ends (RFC 4180). NULL is an empty unquoted field; the empty string is "" (as
 * PostgreSQL's COPY … CSV writes them), so the two stay apart.
 */
export function toCsv(columns, rows) {
  const lines = [columns.map((c) => csvCell(c.name)).join(',')];
  for (const r of rows) lines.push(r.map(csvCell).join(','));
  return `${lines.join('\r\n')}\r\n`;
}

/** JSON: an array of objects keyed by column name; values as PostgreSQL's text, NULL as null. */
export function toJson(columns, rows) {
  const names = columns.map((c) => c.name);
  return `${JSON.stringify(rows.map((r) => Object.fromEntries(names.map((n, i) => [n, r[i]]))), null, 2)}\n`;
}

/** A problem with a CSV file; the message is shown to the user. */
export class CsvError extends Error {}

/**
 * Parses CSV (RFC 4180; comma separator; CRLF or LF). An empty unquoted field is null; "" is the empty string.
 * Returns {header, rows}. Every row must have as many fields as the header.
 */
export function parseCsv(text) {
  if (text.includes('\0')) throw new CsvError('The file contains a NUL character; it is not a text CSV file.');
  const src = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
  const records = [];
  let row = [];
  let field = '';
  let quoted = false;
  let wasQuoted = false;
  let i = 0;
  const endField = () => {
    row.push(field === '' && !wasQuoted ? null : field);
    field = '';
    wasQuoted = false;
  };
  const endRow = () => {
    endField();
    records.push(row);
    row = [];
  };
  while (i < src.length) {
    const ch = src[i];
    if (quoted) {
      if (ch === '"') {
        if (src[i + 1] === '"') {
          field += '"';
          i += 2;
          continue;
        }
        quoted = false;
        i += 1;
        if (i < src.length && src[i] !== ',' && src[i] !== '\n' && src[i] !== '\r') {
          throw new CsvError(`Line ${records.length + 1}: text after a closing quote.`);
        }
        continue;
      }
      field += ch;
      i += 1;
      continue;
    }
    if (ch === '"' && field === '' && !wasQuoted) {
      quoted = true;
      wasQuoted = true;
      i += 1;
    } else if (ch === ',') {
      endField();
      i += 1;
    } else if (ch === '\r' || ch === '\n') {
      endRow();
      i += ch === '\r' && src[i + 1] === '\n' ? 2 : 1;
    } else {
      field += ch;
      i += 1;
    }
  }
  if (quoted) throw new CsvError('The file ends inside a quoted field.');
  if (field !== '' || wasQuoted || row.length > 0) endRow();
  if (records.length === 0) throw new CsvError('The file is empty.');
  const [header, ...rows] = records;
  header.forEach((h, n) => {
    if (h === null || h.trim() === '') throw new CsvError(`Column ${n + 1} of the header has no name.`);
  });
  if (new Set(header).size !== header.length) throw new CsvError('The header names a column twice.');
  rows.forEach((r, n) => {
    if (r.length !== header.length) throw new CsvError(`Line ${n + 2} has ${r.length} fields; the header has ${header.length}.`);
  });
  return { header, rows };
}

/**
 * INSERT statements for the editor, in one transaction (BEGIN … COMMIT), at most 100 rows per statement. The header
 * names must be columns of the table.
 */
export function insertScript(schema, table, tableColumns, csv, fileName) {
  const known = new Set(tableColumns.map((c) => c.name));
  const unknown = csv.header.filter((h) => !known.has(h));
  if (unknown.length) throw new CsvError(`Not columns of ${schema}.${table}: ${unknown.join(', ')}.`);
  if (csv.rows.length === 0) throw new CsvError('The file has a header but no rows.');
  if (csv.rows.length > IMPORT_MAX_ROWS) throw new CsvError(`The file has ${csv.rows.length} rows; at most ${IMPORT_MAX_ROWS} can be imported at once.`);
  const target = qualified(schema, table);
  const cols = csv.header.map(quoteIdent).join(', ');
  const parts = [
    `-- Import of ${String(fileName).replace(/[\r\n]/g, ' ')} into ${target}: ${csv.rows.length} rows.`,
    '-- Nothing has run yet. Review, then use Run script: it runs in one transaction and stops at the first error;',
    '-- if it stops, press Roll back.',
    'BEGIN;',
  ];
  for (let start = 0; start < csv.rows.length; start += ROWS_PER_INSERT) {
    const values = csv.rows.slice(start, start + ROWS_PER_INSERT).map((r) => `(${r.map((v) => (v === null ? 'NULL' : quoteLiteral(v))).join(', ')})`);
    parts.push(`INSERT INTO ${target} (${cols}) VALUES\n${values.join(',\n')};`);
  }
  parts.push('COMMIT;');
  const script = `${parts.join('\n')}\n`;
  if (new TextEncoder().encode(script).length > SCRIPT_MAX_BYTES) {
    throw new CsvError('The generated script would be larger than the editor can send (900 KB). Split the file into smaller parts.');
  }
  return script;
}
