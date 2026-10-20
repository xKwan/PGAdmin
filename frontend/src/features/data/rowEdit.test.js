import { describe, expect, it } from 'vitest';
import { browseSql, buildSave, describeKey, isEditable } from './rowEdit.js';

const columns = [{ name: 'id' }, { name: 'Name' }, { name: 'note' }];
const rows = [
  ['1', 'Ada', null],
  ['2', "O'Brien", 'x'],
  ['3', 'Bob', 'C:\\tmp'],
];
const base = { schema: 'shop', table: 'Customers', columns, primaryKey: ['id'], rows };

describe('table data editing (admin Phase 6)', () => {
  it('browses by primary key, one row more than a page', () => {
    expect(browseSql('shop', 'Customers', columns, ['id'], 100, 200)).toBe('SELECT id, "Name", note\nFROM shop."Customers"\nORDER BY id\nLIMIT 101 OFFSET 200');
    expect(browseSql('s', 't', columns, [], 50, 0)).toBe('SELECT id, "Name", note\nFROM s.t\nORDER BY id\nLIMIT 51 OFFSET 0');  });

  it('updates by key and only if the changed columns still hold what was loaded', () => {
    const out = buildSave({ ...base, changes: { 1: { Name: 'Ann', note: null }, 0: { Name: 'Ada' } } });
    expect(out).toEqual([
      {
        kind: 'update',
        row: 1,
        sql: 'UPDATE shop."Customers" SET "Name" = \'Ann\', note = NULL\nWHERE id = \'2\' AND "Name"::text IS NOT DISTINCT FROM \'O\'\'Brien\' AND note::text IS NOT DISTINCT FROM \'x\';',
      },
    ]); // row 0 didn't really change
  });

  it('quotes hostile values and backslashes as literals', () => {
    const [u] = buildSave({ ...base, changes: { 2: { note: "'); DROP TABLE x; --" } } });
    expect(u.sql).toBe("UPDATE shop.\"Customers\" SET note = '''); DROP TABLE x; --'\nWHERE id = '3' AND note::text IS NOT DISTINCT FROM E'C:\\\\tmp';");
    const [n] = buildSave({ ...base, changes: { 0: { note: 'set' } } });
    expect(n.sql).toContain('note::text IS NOT DISTINCT FROM NULL');
  });

  it('deletes first, skips changes to deleted rows, and inserts last', () => {
    const out = buildSave({ ...base, deleted: [2, 0], changes: { 0: { Name: 'gone anyway' } }, inserted: [{ id: '9', Name: 'New', note: undefined }, {}] });
    expect(out.map((s) => s.sql)).toEqual([
      'DELETE FROM shop."Customers" WHERE id = \'1\';',
      'DELETE FROM shop."Customers" WHERE id = \'3\';',
      'INSERT INTO shop."Customers" (id, "Name")\nVALUES (\'9\', \'New\');',
      'INSERT INTO shop."Customers" DEFAULT VALUES;',
    ]);
    expect(out.map((s) => s.kind)).toEqual(['delete', 'delete', 'insert', 'insert']);
  });

  it('uses every column of a composite key', () => {
    const out = buildSave({ schema: 's', table: 't', columns: [{ name: 'a' }, { name: 'b' }, { name: 'v' }], primaryKey: ['a', 'b'], rows: [['1', 'x', 'old']], changes: { 0: { v: 'new' } } });
    expect(out[0].sql).toBe("UPDATE s.t SET v = 'new'\nWHERE a = '1' AND b = 'x' AND v::text IS NOT DISTINCT FROM 'old';");
    expect(describeKey(['a', 'b'], ['1', 'x', 'old'], { a: 0, b: 1 })).toBe('a = 1, b = x');
  });

  it('refuses tables without a key, and keys that were not loaded', () => {
    expect(() => buildSave({ ...base, primaryKey: [] })).toThrow('primary key');
    expect(() => buildSave({ ...base, primaryKey: ['missing'] })).toThrow('not among the loaded columns');
  });

  it('knows which columns can be set', () => {
    expect(isEditable({ name: 'a' })).toBe(true);
    expect(isEditable({ name: 'a', identity: 'd' })).toBe(true);
    expect(isEditable({ name: 'a', identity: 'a' })).toBe(false);
    expect(isEditable({ name: 'a', generated: 's' })).toBe(false);
  });
});
