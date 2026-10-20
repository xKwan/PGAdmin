import { getCompletion, quoteIdent } from '../../api/metadata.js';

// Schema-aware completion (admin Phase 10, decision A15). Names come from the catalog, read as your own role on the
// session's companion connection, one schema at a time and only when the editor asks; each schema is read once per
// session (and again after you change objects). Only objects your role can use are offered. Nothing is run.

/** Splits the dotted name before the cursor into parts, keeping quoted parts whole: `s."My t".co` → 3 parts. */
export function splitPath(text) {
  const parts = [];
  let cur = '';
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (ch === '"') {
      if (quoted && text[i + 1] === '"') {
        cur += '""';
        i++;
        continue;
      }
      quoted = !quoted;
      cur += ch;
    } else if (ch === '.' && !quoted) {
      parts.push(cur);
      cur = '';
    } else {
      cur += ch;
    }
  }
  parts.push(cur);
  return parts;
}

/** A name as PostgreSQL reads it: quoted names exactly, bare names folded to lower case. */
export function nameOf(part) {
  if (part.startsWith('"')) {
    const inner = part.endsWith('"') && part.length > 1 ? part.slice(1, -1) : part.slice(1);
    return inner.replaceAll('""', '"');
  }
  return part.toLowerCase();
}

const REF = /\b(?:from|join|update|into|table)\s+((?:"(?:[^"]|"")+"|[A-Za-z_][\w$]*)(?:\s*\.\s*(?:"(?:[^"]|"")+"|[A-Za-z_][\w$]*))?)(?:\s+(?:as\s+)?("(?:[^"]|"")+"|[A-Za-z_][\w$]*))?/gi;
const NOT_ALIAS = new Set(['where', 'join', 'on', 'using', 'left', 'right', 'inner', 'outer', 'full', 'cross', 'natural', 'group', 'order', 'limit',
  'set', 'values', 'select', 'returning', 'as', 'union', 'except', 'intersect', 'window', 'having', 'offset', 'fetch', 'for', 'lateral', 'default']);

/** Tables named after FROM, JOIN, UPDATE, INTO or TABLE in the text, with their aliases: alias (or name) → {schema, name}. */
export function referencedTables(text) {
  const out = new Map();
  for (const m of text.slice(0, 50_000).matchAll(REF)) {
    const parts = splitPath(m[1].replace(/\s*\.\s*/, '.')).map(nameOf);
    const ref = parts.length === 2 ? { schema: parts[0], name: parts[1] } : { schema: null, name: parts[0] };
    out.set(ref.name, ref);
    if (m[2] && !NOT_ALIAS.has(m[2].toLowerCase())) out.set(nameOf(m[2]), ref);
  }
  return out;
}

/**
 * The catalog for one session, loaded lazily and cached: `schemas()` and `schema(name)` return promises. The loaders
 * are `listSchemas` and `getCompletion` (replaceable in tests).
 */
export function createCatalog(sessionId, { schemas: loadSchemas, schema: loadSchema = (s) => getCompletion(sessionId, s) } = {}) {
  let schemaList = null;
  const bySchema = new Map();
  return {
    schemas() {
      if (!schemaList) schemaList = loadSchemas().then((r) => r.schemas.map((s) => s.name), () => { schemaList = null; return []; });
      return schemaList;
    },
    schema(name) {
      if (!bySchema.has(name)) {
        bySchema.set(name, loadSchema(name).then(
          (r) => {
            const relations = new Map(r.relations.map((x) => [x.name, { ...x, columns: [] }]));
            for (const c of r.columns) relations.get(c.relation)?.columns.push(c);
            return { relations, routines: r.routines || [], searchPath: r.searchPath || [] };
          },
          () => {
            bySchema.delete(name);
            return { relations: new Map(), routines: [], searchPath: [] };
          },
        ));
      }
      return bySchema.get(name);
    },
  };
}

const kindType = { table: 'class', partitioned_table: 'class', foreign_table: 'class', view: 'class', materialized_view: 'class', sequence: 'constant' };

function relationOptions(info, schema) {
  return [...info.relations.values()].map((r) => ({ label: r.name, apply: quoteIdent(r.name), type: kindType[r.kind] || 'class', detail: schema ? `${schema} · ${r.kind.replaceAll('_', ' ')}` : r.kind.replaceAll('_', ' ') }));
}

function columnOptions(rel, boost = 0) {
  return rel.columns.map((c) => ({ label: c.name, apply: quoteIdent(c.name), type: 'property', detail: c.type, boost }));
}

/** The schemas searched for an unqualified name: the session's search path, else public. */
async function pathSchemas(catalog) {
  const all = await catalog.schemas();
  const first = all.includes('public') ? await catalog.schema('public') : { searchPath: [] };
  const path = first.searchPath.length ? first.searchPath : ['public'];
  return path.filter((s) => all.includes(s));
}

async function findRelation(catalog, ref) {
  const schemas = ref.schema ? [ref.schema] : await pathSchemas(catalog);
  for (const s of schemas) {
    const rel = (await catalog.schema(s)).relations.get(ref.name);
    if (rel) return rel;
  }
  return null;
}

/**
 * A CodeMirror completion source. `getCatalog()` returns the current session's catalog, or null without a session.
 * Completes schema names, relations (unqualified from the search path, or after `schema.`), columns (after
 * `table.` or an alias, and of the tables the statement names) and routines after `schema.`.
 */
export function catalogCompletionSource(getCatalog) {
  return async (context) => {
    const catalog = getCatalog();
    if (!catalog) return null;
    const word = context.matchBefore(/(?:(?:"(?:[^"]|"")*"|[\w$]+)\.)*(?:"(?:[^"]|"")*"?|[\w$]*)$/);
    if (!word || (word.from === word.to && !context.explicit)) return null;
    const parts = splitPath(word.text);
    const last = parts[parts.length - 1];
    const from = word.to - last.length;
    const names = parts.slice(0, -1).map(nameOf);
    const doc = context.state.doc.toString();
    let options = [];
    if (names.length === 0) {
      const schemas = await catalog.schemas();
      options.push(...schemas.map((s) => ({ label: s, apply: quoteIdent(s), type: 'namespace', boost: -1 })));
      for (const s of await pathSchemas(catalog)) options.push(...relationOptions(await catalog.schema(s), s));
      for (const ref of new Set(referencedTables(doc).values())) {
        const rel = await findRelation(catalog, ref);
        if (rel) options.push(...columnOptions(rel, 1));
      }
    } else if (names.length === 1) {
      const aliases = referencedTables(doc);
      const ref = aliases.get(names[0]);
      const rel = ref ? await findRelation(catalog, ref) : null;
      if (rel) options = columnOptions(rel);
      else if ((await catalog.schemas()).includes(names[0])) {
        const info = await catalog.schema(names[0]);
        options = [...relationOptions(info), ...info.routines.map((r) => ({ label: r.name, apply: quoteIdent(r.name), type: 'function', detail: r.kind }))];
      } else {
        const direct = await findRelation(catalog, { schema: null, name: names[0] });
        if (direct) options = columnOptions(direct);
      }
    } else if (names.length === 2) {
      const rel = await findRelation(catalog, { schema: names[0], name: names[1] });
      if (rel) options = columnOptions(rel);
    }
    if (options.length === 0) return null;
    // Duplicate labels (the same column in two tables) are offered once.
    const seen = new Set();
    options = options.filter((o) => (seen.has(`${o.type}|${o.label}`) ? false : seen.add(`${o.type}|${o.label}`)));
    return { from, options, validFor: /^(?:"(?:[^"]|"")*"?|[\w$]*)$/ };
  };
}
