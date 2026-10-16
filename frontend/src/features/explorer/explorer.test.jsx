import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { describeMetadataError, qualified, quoteIdent } from '../../api/metadata.js';
import { envelope, http, HttpResponse, server } from '../../mocks/server.js';
import { axeViolations } from '../../test/a11y.js';
import { randomText, seededRandom } from '../../test/random.js';
import ObjectExplorer from './ObjectExplorer.jsx';

const SID = 'AbCdEfGhIjKlMnOpQrStUv';
const PAYLOAD = 'a"; DROP TABLE shop.orders; --';

function mockCatalog({ schemasFail = 0, objectsError = null } = {}) {
  const seen = [];
  let failures = schemasFail;
  server.use(
    http.get(`/api/metadata/${SID}/schemas`, ({ request }) => {
      const all = new URL(request.url).searchParams.get('all') === 'true';
      seen.push(['schemas', all]);
      if (failures > 0) {
        failures -= 1;
        return envelope(409, 'COMPANION_BUSY', 'busy');
      }
      const schemas = [
        { name: 'shop', owner: 'o', accessible: true },
        { name: 'we"ird', owner: 'o', accessible: true },
      ];
      if (all) schemas.push({ name: 'hidden', owner: 'o', accessible: false });
      return HttpResponse.json({ schemas, loadedAt: '2026-10-16T08:00:00Z' });
    }),
    http.get(`/api/metadata/${SID}/objects`, ({ request }) => {
      const q = new URL(request.url).searchParams;
      seen.push(['objects', q.get('schema'), q.get('all') === 'true']);
      if (objectsError) return objectsError;
      if (q.get('schema') === 'we"ird') return HttpResponse.json({ objects: [], loadedAt: 'x' });
      const objects = [
        { kind: 'table', name: 'orders', accessible: true, comment: 'Customer orders' },
        { kind: 'table', name: PAYLOAD, accessible: true },
        { kind: 'view', name: 'Select', accessible: true },
        { kind: 'sequence', name: 'seq', accessible: true },
        { kind: 'function', name: 'f', arguments: 'a integer', returns: 'integer', accessible: true },
        { kind: 'procedure', name: 'p', arguments: '', accessible: true },
      ];
      if (q.get('all') === 'true') objects.push({ kind: 'table', name: 'secret', accessible: false });
      return HttpResponse.json({ objects, loadedAt: 'x' });
    }),
    http.get(`/api/metadata/${SID}/relation`, ({ request }) => {
      const q = new URL(request.url).searchParams;
      seen.push(['relation', q.get('schema'), q.get('name')]);
      if (q.get('name') === 'gone') return envelope(404, 'NOT_FOUND', 'Not found');
      return HttpResponse.json({
        schema: q.get('schema'),
        name: q.get('name'),
        kind: 'table',
        accessible: true,
        columns: [
          { name: 'id', type: 'integer', nullable: false, default: "nextval('orders_id_seq'::regclass)" },
          { name: 'Customer Name', type: 'text', nullable: true },
        ],
        indexes: [
          { name: 'orders_pkey', definition: 'CREATE UNIQUE INDEX …', primary: true, unique: true },
          { name: 'orders_code_key', definition: 'CREATE UNIQUE INDEX …', primary: false, unique: true },
        ],
        constraints: [{ name: 'orders_pkey', type: 'primary_key', definition: 'PRIMARY KEY (id)' }],
        loadedAt: 'x',
      });
    }),
  );
  return seen;
}

describe('quoteIdent', () => {
  it('leaves simple lower-case names bare and quotes everything else', () => {
    expect(quoteIdent('orders')).toBe('orders');
    expect(quoteIdent('_x1$')).toBe('_x1$');
    expect(quoteIdent('select')).toBe('"select"');
    expect(quoteIdent('Orders')).toBe('"Orders"');
    expect(quoteIdent('two words')).toBe('"two words"');
    expect(quoteIdent('表')).toBe('"表"');
    expect(quoteIdent('1abc')).toBe('"1abc"');
    expect(quoteIdent(PAYLOAD)).toBe('"a""; DROP TABLE shop.orders; --"');
    expect(qualified('we"ird', 'x')).toBe('"we""ird".x');
  });

  it('round-trips random names: unquoting the result gives the name back', () => {
    const rnd = seededRandom('quote');
    for (let i = 0; i < 200; i++) {
      const name = randomText(rnd, 1 + Math.floor(rnd() * 12)) + (rnd() < 0.3 ? '"' : '');
      const q = quoteIdent(name);
      const back = q.startsWith('"') ? q.slice(1, -1).replaceAll('""', '"') : q;
      expect(back).toBe(name);
      if (q.startsWith('"')) expect(q.slice(1, -1).replaceAll('""', '')).not.toContain('"');
    }
  });

  it('describes errors', () => {
    expect(describeMetadataError({ code: 'COMPANION_BUSY' })).toMatch(/busy/);
    expect(describeMetadataError({ code: 'METADATA_UNAVAILABLE' })).toMatch(/Limited/);
    expect(describeMetadataError({ code: 'NOT_FOUND' })).toMatch(/Refresh/);
    expect(describeMetadataError({ code: 'X', message: 'm' })).toBe('m');
    expect(describeMetadataError(null)).toBe('The objects could not be loaded.');
  });
});

describe('ObjectExplorer', () => {
  it('loads lazily, groups objects and inserts quoted names', async () => {
    const seen = mockCatalog();
    const onInsert = vi.fn();
    const { container } = render(<ObjectExplorer sessionId={SID} onInsert={onInsert} />);
    const shop = await screen.findByRole('button', { name: 'shop' });
    expect(screen.getByText(/Loaded at/)).toBeInTheDocument();
    expect(seen).toEqual([['schemas', false]]);
    expect(shop).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(shop);
    expect(shop).toHaveAttribute('aria-expanded', 'true');
    expect(await screen.findByText('Tables')).toBeInTheDocument();
    for (const g of ['Views', 'Sequences', 'Functions', 'Procedures']) expect(screen.getByText(g)).toBeInTheDocument();
    expect(screen.getByText('f(a integer)')).toBeInTheDocument();
    expect(screen.getByText(PAYLOAD)).toBeInTheDocument(); // shown as text
    await userEvent.click(screen.getByRole('button', { name: `Insert shop.${PAYLOAD}` }));
    expect(onInsert).toHaveBeenLastCalledWith('shop."a""; DROP TABLE shop.orders; --"');
    await userEvent.click(screen.getByRole('button', { name: 'Insert shop.Select' }));
    expect(onInsert).toHaveBeenLastCalledWith('shop."Select"');
    await userEvent.click(screen.getByRole('button', { name: 'orders' }));
    expect(await screen.findByText('Columns')).toBeInTheDocument();
    expect(screen.getByText('NOT NULL', { exact: false })).toBeInTheDocument();
    expect(screen.getByText(/primary key/)).toBeInTheDocument();
    expect(screen.getByText(/unique/)).toBeInTheDocument();
    expect(screen.getByText('PRIMARY KEY (id)')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Insert column Customer Name' }));
    expect(onInsert).toHaveBeenLastCalledWith('"Customer Name"');
    expect(seen).toContainEqual(['relation', 'shop', 'orders']);
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: 'we"ird' }));
    expect(await screen.findByText('No objects you can use.')).toBeInTheDocument();
  });

  it('shows everything on request, marking what the role cannot use', async () => {
    mockCatalog();
    render(<ObjectExplorer sessionId={SID} onInsert={() => {}} />);
    await screen.findByRole('button', { name: 'shop' });
    await userEvent.click(screen.getByRole('checkbox', { name: 'Show everything in the catalog' }));
    const hidden = await screen.findByRole('button', { name: 'hidden' });
    expect(hidden).toBeDisabled();
    expect(within(hidden.closest('li')).getByText('no access')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'shop' }));
    const secret = await screen.findByText('secret');
    expect(within(secret.closest('li')).getByText('no access')).toBeInTheDocument();
    expect(within(secret.closest('li')).queryByRole('button', { name: 'secret' })).toBeNull(); // not expandable
  });

  it('reports a busy companion and recovers on retry; refresh reloads', async () => {
    const seen = mockCatalog({ schemasFail: 1 });
    render(<ObjectExplorer sessionId={SID} onInsert={() => {}} />);
    expect(await screen.findByRole('alert')).toHaveTextContent(/busy/);
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await screen.findByRole('button', { name: 'shop' });
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(seen.filter((s) => s[0] === 'schemas')).toHaveLength(3));
  });

  it('reports object and relation errors in place', async () => {
    mockCatalog({ objectsError: envelope(409, 'METADATA_UNAVAILABLE', 'x') });
    render(<ObjectExplorer sessionId={SID} onInsert={() => {}} />);
    await userEvent.click(await screen.findByRole('button', { name: 'shop' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Limited/);
  });

  it('a relation that disappeared says to refresh', async () => {
    mockCatalog();
    server.use(
      http.get(`/api/metadata/${SID}/objects`, () => HttpResponse.json({ objects: [{ kind: 'table', name: 'gone', accessible: true }], loadedAt: 'x' })),
    );
    render(<ObjectExplorer sessionId={SID} onInsert={() => {}} />);
    await userEvent.click(await screen.findByRole('button', { name: 'shop' }));
    await userEvent.click(await screen.findByRole('button', { name: 'gone' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Refresh the tree/);
  });
});
