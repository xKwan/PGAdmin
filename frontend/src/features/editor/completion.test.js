import { CompletionContext } from '@codemirror/autocomplete';
import { EditorState } from '@codemirror/state';
import { describe, expect, it, vi } from 'vitest';
import { catalogCompletionSource, createCatalog, nameOf, referencedTables, splitPath } from './completion.js';

const SCHEMAS = { schemas: [{ name: 'public' }, { name: 'shop' }, { name: 'My Schema' }] };
const DATA = {
  public: {
    searchPath: ['public'],
    relations: [{ name: 'items', kind: 'table' }, { name: 'Order Lines', kind: 'view' }],
    columns: [
      { relation: 'items', name: 'id', type: 'integer' },
      { relation: 'items', name: 'label', type: 'text' },
      { relation: 'Order Lines', name: 'Qty', type: 'numeric' },
    ],
    routines: [],
  },
  shop: {
    searchPath: ['public'],
    relations: [{ name: 'orders', kind: 'table' }],
    columns: [{ relation: 'orders', name: 'total', type: 'numeric(10,2)' }],
    routines: [{ name: 'place_order', kind: 'procedure' }],
  },
  'My Schema': { searchPath: ['public'], relations: [{ name: 't', kind: 'table' }], columns: [{ relation: 't', name: 'x', type: 'int' }], routines: [] },
};

function setup() {
  const calls = [];
  const catalog = createCatalog('S1', {
    schemas: vi.fn(async () => SCHEMAS),
    schema: async (s) => {
      calls.push(s);
      return DATA[s];
    },
  });
  return { catalog, calls, source: catalogCompletionSource(() => catalog) };
}

async function complete(source, doc, explicit = false) {
  const pos = doc.indexOf('|');
  const text = doc.replace('|', '');
  const state = EditorState.create({ doc: text });
  const r = await source(new CompletionContext(state, pos, explicit));
  return r ? { from: r.from, labels: r.options.map((o) => o.label), applies: Object.fromEntries(r.options.map((o) => [o.label, o.apply])) } : null;
}

describe('schema-aware completion (admin Phase 10)', () => {
  it('splits and reads names as PostgreSQL does', () => {
    expect(splitPath('s."My.t".co')).toEqual(['s', '"My.t"', 'co']);
    expect(splitPath('"a""b".')).toEqual(['"a""b"', '']);
    expect(nameOf('Items')).toBe('items');
    expect(nameOf('"Items"')).toBe('Items');
    expect(nameOf('"a""b"')).toBe('a"b');
    expect(nameOf('"open')).toBe('open');
  });

  it('finds the tables a statement names, with aliases', () => {
    const refs = referencedTables('SELECT * FROM shop.orders o JOIN items AS i ON i.id = o.item WHERE x');
    expect(refs.get('o')).toEqual({ schema: 'shop', name: 'orders' });
    expect(refs.get('i')).toEqual({ schema: null, name: 'items' });
    expect(refs.get('orders')).toEqual({ schema: 'shop', name: 'orders' });
    expect(referencedTables('SELECT 1 FROM items WHERE true').has('where')).toBe(false);
    expect(referencedTables('UPDATE "My Schema".t SET x = 1').get('t')).toEqual({ schema: 'My Schema', name: 't' });
  });

  it('offers schemas, relations on the search path and columns of the named tables', async () => {
    const { source } = setup();
    const r = await complete(source, 'SELECT it| FROM items');
    expect(r.labels).toEqual(expect.arrayContaining(['public', 'shop', 'My Schema', 'items', 'Order Lines', 'id', 'label']));
    expect(r.labels).not.toContain('orders'); // not on the search path
    expect(r.applies['My Schema']).toBe('"My Schema"');
    expect(r.applies['Order Lines']).toBe('"Order Lines"');
    expect(r.from).toBe(7);
  });

  it('offers what is inside a schema, a table or an alias after a dot', async () => {
    const { source, calls } = setup();
    expect((await complete(source, 'SELECT * FROM shop.|')).labels).toEqual(['orders', 'place_order']);
    expect((await complete(source, 'SELECT o.| FROM shop.orders o')).labels).toEqual(['total']);
    expect((await complete(source, 'SELECT items.la|')).labels).toEqual(['id', 'label']);
    expect((await complete(source, 'SELECT "Order Lines".|')).applies).toEqual({ Qty: '"Qty"' });
    expect((await complete(source, 'SELECT * FROM "My Schema".t WHERE "My Schema".t.|')).labels).toEqual(['x']);
    expect(await complete(source, 'SELECT nope.|')).toBeNull();
    // Each schema was read once, however often the editor asked.
    expect(calls.filter((s) => s === 'shop')).toHaveLength(1);
  });

  it('stays quiet without a session or without anything typed', async () => {
    expect(await complete(catalogCompletionSource(() => null), 'SELECT it|')).toBeNull();
    const { source } = setup();
    expect(await complete(source, 'SELECT |')).toBeNull();
    expect((await complete(source, 'SELECT |', true)).labels).toContain('items');
  });

  it('forgets a schema that failed to load, so it is tried again', async () => {
    let fail = true;
    const catalog = createCatalog('S1', {
      schemas: async () => SCHEMAS,
      schema: async (s) => {
        if (fail) throw new Error('busy');
        return DATA[s];
      },
    });
    const source = catalogCompletionSource(() => catalog);
    expect(await complete(source, 'SELECT * FROM shop.|')).toBeNull();
    fail = false;
    expect((await complete(source, 'SELECT * FROM shop.|')).labels).toContain('orders');
  });
});
