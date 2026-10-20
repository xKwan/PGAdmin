import { describe, expect, it } from 'vitest';
import { quoteIdent } from '../../api/metadata.js';
import { randomText, seededRandom } from '../../test/random.js';
import { columnSql, commentText, compareSnapshots } from './schemaDiff.js';

const EMPTY = { relations: [], columns: [], constraints: [], indexes: [], triggers: [], sequences: [], routines: [] };

function snap(schema, parts, database = 'sales') {
  return { database, schema, loadedAt: '2026-10-20T08:00:00Z', snapshot: { ...EMPTY, ...parts } };
}

const col = (table, name, type, extra = {}) => ({ table, name, type, notNull: false, ...extra });
const seqOpts = { type: 'bigint', increment: '1', min: '1', max: '9223372036854775807', start: '1', cache: '1', cycle: false };

/** The SQL's statements and comments without the header. */
function body(sql) {
  return sql.split('\n').slice(7).join('\n');
}

describe('schema comparison (admin Phase 8)', () => {
  it('finds no differences between equal schemas, even in different schemas or databases', () => {
    const parts = {
      relations: [{ name: 'items', kind: 'table' }, { name: 'v', kind: 'view', definition: ' SELECT 1' }],
      columns: [col('items', 'id', 'integer', { notNull: true })],
      constraints: [{ table: 'items', name: 'items_pkey', type: 'primary_key', definition: 'PRIMARY KEY (id)' }],
    };
    const r = compareSnapshots(snap('a', parts), snap('b', parts, 'other'));
    expect(r.differences).toEqual([]);
    expect(r.sql).toContain('-- No differences that can be written as SQL.');
    expect(r.sql).not.toContain('BEGIN');
    expect(r.sql).toContain('-- Source: sales, schema a, read 2026-10-20T08:00:00Z');
    expect(r.sql).toContain('-- Target: other, schema b,');
  });

  it('writes what makes the target like the source, in dependency order, in one transaction', () => {
    const source = snap('shop', {
      relations: [
        { name: 'items', kind: 'table' },
        { name: 'items_id_seq', kind: 'sequence' },
        { name: 'orders', kind: 'table' },
        { name: 'big', kind: 'view', definition: ' SELECT id\n   FROM items\n  WHERE id > 10' },
        { name: 'counts', kind: 'materialized_view', definition: ' SELECT count(*) AS n\n   FROM items' },
        { name: 'logs', kind: 'table', unlogged: true },
      ],
      columns: [
        col('items', 'id', 'integer', { notNull: true, default: "nextval('items_id_seq'::regclass)" }),
        col('items', 'label', 'character varying(80)', { notNull: true, default: "'x'::character varying" }),
        col('items', 'price', 'numeric(10,2)'),
        col('orders', 'id', 'bigint', { notNull: true, identity: 'a' }),
        col('orders', 'item', 'integer'),
        col('orders', 'total', 'numeric', { generated: 's', default: '(item * 2)' }),
        col('logs', 'at', 'timestamp with time zone', { collation: null }),
      ],
      constraints: [
        { table: 'items', name: 'items_pkey', type: 'primary_key', definition: 'PRIMARY KEY (id)' },
        { table: 'items', name: 'price_ok', type: 'check', definition: 'CHECK (price >= 0::numeric)' },
        { table: 'orders', name: 'orders_item_fkey', type: 'foreign_key', definition: 'FOREIGN KEY (item) REFERENCES items(id)' },
      ],
      indexes: [
        { table: 'items', name: 'items_label', definition: 'CREATE INDEX items_label ON items USING btree (label)' },
        { table: 'counts', name: 'counts_n', definition: 'CREATE UNIQUE INDEX counts_n ON counts USING btree (n)' },
      ],
      triggers: [{ table: 'items', name: 'touch', definition: 'CREATE TRIGGER touch BEFORE UPDATE ON items FOR EACH ROW EXECUTE FUNCTION stamp()' }],
      sequences: [{ name: 'items_id_seq', ...seqOpts, type: 'integer', max: '2147483647' }],
      routines: [{ name: 'stamp', arguments: '', kind: 'function', definition: 'CREATE OR REPLACE FUNCTION stamp()\n RETURNS trigger\n LANGUAGE plpgsql\nAS $function$BEGIN RETURN NEW; END$function$' }],
    });
    const target = snap('shop_test', {
      relations: [
        { name: 'items', kind: 'table' },
        { name: 'items_id_seq', kind: 'sequence' },
        { name: 'big', kind: 'view', definition: ' SELECT id\n   FROM items' },
        { name: 'old', kind: 'table' },
        { name: 'logs', kind: 'table' },
      ],
      columns: [
        col('items', 'id', 'integer', { notNull: true, default: "nextval('items_id_seq'::regclass)" }),
        col('items', 'label', 'text'),
        col('items', 'legacy', 'text'),
        col('old', 'x', 'integer'),
        col('logs', 'at', 'timestamp with time zone'),
      ],
      constraints: [
        { table: 'items', name: 'items_pkey', type: 'primary_key', definition: 'PRIMARY KEY (id)' },
        { table: 'items', name: 'price_ok', type: 'check', definition: 'CHECK (price > 0::numeric)' },
        { table: 'items', name: 'legacy_ok', type: 'check', definition: 'CHECK (legacy <> \'\'::text)' },
      ],
      indexes: [{ table: 'items', name: 'items_legacy', definition: 'CREATE INDEX items_legacy ON items USING btree (legacy)' }],
      sequences: [{ name: 'items_id_seq', ...seqOpts, type: 'integer', max: '2147483647', increment: '5' }],
      routines: [{ name: 'gone', arguments: 'a integer, b text', kind: 'procedure', definition: 'CREATE OR REPLACE PROCEDURE gone(a integer, b text) LANGUAGE sql AS $p$SELECT 1$p$' }],
    });
    const r = compareSnapshots(source, target);
    const got = (status) => r.differences.filter((d) => d.status === status).map((d) => d.name);
    expect(got('create')).toEqual(expect.arrayContaining(['orders', 'items.price', 'items.items_label', 'items.touch', 'stamp()', 'counts']));
    expect(got('change')).toEqual(expect.arrayContaining(['items_id_seq', 'items.label', 'items.price_ok', 'big', 'logs']));
    expect(got('drop')).toEqual(expect.arrayContaining(['old', 'items.legacy', 'items.legacy_ok', 'items.items_legacy', 'gone(a integer, b text)']));
    expect(got('review')).toEqual([]);
    const expected = [
      'ALTER TABLE items DROP CONSTRAINT price_ok;',
      '-- ALTER TABLE items DROP CONSTRAINT legacy_ok;',
      '-- DROP INDEX items_legacy;',
      '-- ALTER TABLE items DROP COLUMN legacy;',
      '-- DROP TABLE old;',
      '-- DROP PROCEDURE gone(a integer, b text);',
      'ALTER SEQUENCE items_id_seq AS integer INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START WITH 1 CACHE 1 NO CYCLE;',
      'CREATE TABLE orders (\n    id bigint GENERATED ALWAYS AS IDENTITY,\n    item integer,\n    total numeric GENERATED ALWAYS AS ((item * 2)) STORED\n);',
      'ALTER TABLE items ALTER COLUMN label TYPE character varying(80);',
      "ALTER TABLE items ALTER COLUMN label SET DEFAULT 'x'::character varying;",
      'ALTER TABLE items ALTER COLUMN label SET NOT NULL;',
      'ALTER TABLE items ADD COLUMN price numeric(10,2);',
      'ALTER TABLE logs SET UNLOGGED;',
      'CREATE OR REPLACE FUNCTION stamp()\n RETURNS trigger\n LANGUAGE plpgsql\nAS $function$BEGIN RETURN NEW; END$function$;',
      'ALTER TABLE items ADD CONSTRAINT price_ok CHECK (price >= 0::numeric);',
      'ALTER TABLE orders ADD CONSTRAINT orders_item_fkey FOREIGN KEY (item) REFERENCES items(id);',
      'CREATE OR REPLACE VIEW big AS\n SELECT id\n   FROM items\n  WHERE id > 10;',
      'CREATE MATERIALIZED VIEW counts AS\n SELECT count(*) AS n\n   FROM items;',
      'CREATE INDEX items_label ON items USING btree (label);',
      'CREATE UNIQUE INDEX counts_n ON counts USING btree (n);',
      'CREATE TRIGGER touch BEFORE UPDATE ON items FOR EACH ROW EXECUTE FUNCTION stamp();',
    ];
    let at = r.sql.indexOf('BEGIN;\nSET LOCAL search_path TO shop_test;\n');
    expect(at).toBeGreaterThan(0);
    for (const s of expected) {
      const next = r.sql.indexOf(s, at);
      expect(next, s).toBeGreaterThan(at);
      at = next;
    }
    expect(r.sql.endsWith('\nCOMMIT;\n')).toBe(true);
    expect(r.sql).toContain('-- Objects and columns that exist only in the target are not dropped');

    const withDrops = compareSnapshots(source, target, { includeDrops: true });
    expect(withDrops.sql).toContain('\nDROP TABLE old;\n');
    expect(withDrops.sql).toContain('\nALTER TABLE items DROP COLUMN legacy;\n');
    expect(withDrops.sql).toContain('-- It DROPS objects and columns that exist only in the target');
    expect(withDrops.differences).toEqual(r.differences);
  });

  it('reports what it does not generate instead of guessing', () => {
    const source = snap('a', {
      relations: [
        { name: 'events', kind: 'partitioned_table' },
        { name: 't', kind: 'table' },
        { name: 'mv', kind: 'materialized_view', definition: ' SELECT 2' },
        { name: 'f', kind: 'view', definition: ' SELECT 1' },
      ],
      columns: [col('events', 'at', 'date'), col('t', 'id', 'integer', { identity: 'd', notNull: true })],
      routines: [{ name: 'p', arguments: '', kind: 'procedure', definition: 'CREATE OR REPLACE PROCEDURE p() …' }],
    });
    const target = snap('a', {
      relations: [{ name: 't', kind: 'table' }, { name: 'mv', kind: 'view', definition: ' SELECT 1' }, { name: 'f', kind: 'materialized_view', definition: ' SELECT 1' }],
      columns: [col('t', 'id', 'integer', { notNull: true })],
      routines: [{ name: 'p', arguments: '', kind: 'function', definition: 'CREATE OR REPLACE FUNCTION p() …' }],
    });
    const r = compareSnapshots(source, target);
    expect(r.differences.filter((d) => d.status === 'review').map((d) => d.name)).toEqual(['events', 't.id']);
    expect(r.sql).toContain('-- Not generated: events is a partitioned table; write its SQL by hand.');
    expect(r.sql).toContain('-- Not generated: column t.id is an identity or generated column');
    expect(body(r.sql)).not.toContain('CREATE TABLE events');
    // A view and a materialised view swap kinds: dropped and created again; a routine that changed kind likewise.
    expect(r.sql).toContain('DROP VIEW mv;');
    expect(r.sql).toContain('CREATE MATERIALIZED VIEW mv AS\n SELECT 2;');
    expect(r.sql).toContain('DROP MATERIALIZED VIEW f;');
    expect(r.sql).toContain('CREATE VIEW f AS\n SELECT 1;');
    expect(r.sql).toContain('DROP FUNCTION p();');
  });

  it('writes column definitions', () => {
    expect(columnSql(col('t', 'Name', 'text', { collation: '"C"', notNull: true, default: "'a'::text" }))).toBe('"Name" text COLLATE "C" DEFAULT \'a\'::text NOT NULL');
    expect(columnSql(col('t', 'id', 'integer', { identity: 'd', notNull: true }))).toBe('id integer GENERATED BY DEFAULT AS IDENTITY');
    expect(columnSql(col('t', 'v', 'integer', { generated: 'v', default: '(id + 1)' }))).toBe('v integer GENERATED ALWAYS AS ((id + 1)) VIRTUAL');
    expect(commentText('a\nb\r\u2028c\u0000')).toBe('a?b??c?');
  });

  it('never lets a name end a comment or slip a DROP past the option (randomised names)', () => {
    const rnd = seededRandom('compare-names');
    for (let n = 0; n < 40; n++) {
      const name = randomText(rnd, 1 + Math.floor(rnd() * 12)).replaceAll('\u0000', '') || 'x';
      const other = `${name}_${n}`;
      const source = snap(`${name} src`, {
        relations: [{ name: other, kind: 'partitioned_table' }],
        columns: [col(other, name, 'integer')],
      }, `db\n${name}`);
      const target = snap(`${name}\ntgt`, {
        relations: [{ name, kind: 'table' }, { name: `${name}v`, kind: 'view', definition: ' SELECT 1' }],
        columns: [col(name, name, 'integer')],
        indexes: [{ table: name, name: `${name}i`, definition: `CREATE INDEX ${quoteIdent(`${name}i`)} ON ${quoteIdent(name)} USING btree (x)` }],
        routines: [{ name, arguments: '', kind: 'function', definition: 'x' }],
      });
      const { sql } = compareSnapshots(source, target);
      const live = sql.split(/\r\n|\r|\n/).filter((l) => !l.startsWith('--'));
      expect(live.filter((l) => /\bDROP\b/.test(l)), JSON.stringify(name)).toEqual([]);
      // Header lines are comments however odd the names are.
      expect(sql.split(/\r\n|\r|\n/).slice(0, 7).every((l) => l.startsWith('--'))).toBe(true);
      const { sql: dropping } = compareSnapshots(source, target, { includeDrops: true });
      expect(dropping).toContain(`DROP TABLE ${quoteIdent(name)};`);
      expect(dropping).toContain(`DROP FUNCTION ${quoteIdent(name)}();`);
      expect(dropping).toContain(`SET LOCAL search_path TO ${quoteIdent(`${name}\ntgt`)};`);
    }
  });
});
