import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { envelope, http, HttpResponse, server } from '../../mocks/server.js';
import { axeViolations } from '../../test/a11y.js';
import ObjectExplorer from './ObjectExplorer.jsx';

const SID = 'AbCdEfGhIjKlMnOpQrStUv';

function mock({ extensions = [{ name: 'plpgsql', version: '1.0', schema: 'pg_catalog', comment: 'PL/pgSQL procedural language' }], extensionsError = null } = {}) {
  const seen = [];
  server.use(
    http.get(`/api/metadata/${SID}/schemas`, () => {
      seen.push('schemas');
      return HttpResponse.json({ schemas: [{ name: 'shop', owner: 'o', accessible: true }], loadedAt: '2026-10-19T08:00:00Z' });
    }),
    http.get(`/api/metadata/${SID}/objects`, () =>
      HttpResponse.json({
        objects: [
          { kind: 'partitioned_table', name: 'events', accessible: true },
          { kind: 'table', name: 'events_2026', accessible: true, partition: true },
          { kind: 'table', name: 'orders', accessible: true },
        ],
        loadedAt: 'x',
      }),
    ),
    http.get(`/api/metadata/${SID}/relation`, ({ request }) =>
      HttpResponse.json({
        schema: 'shop',
        name: new URL(request.url).searchParams.get('name'),
        kind: 'table',
        accessible: true,
        columns: [{ name: 'id', type: 'integer', nullable: false }],
        indexes: [],
        constraints: [],
        triggers: [
          { name: 'orders_off', definition: 'CREATE TRIGGER orders_off AFTER INSERT ON shop.orders …', enabled: false },
          { name: 'orders_touch', definition: 'CREATE TRIGGER orders_touch BEFORE UPDATE ON shop.orders …', enabled: true },
        ],
      }),
    ),
    http.get(`/api/metadata/${SID}/extensions`, () => {
      seen.push('extensions');
      return extensionsError || HttpResponse.json({ extensions, loadedAt: 'x' });
    }),
  );
  return seen;
}

describe('object explorer (admin Phase 2)', () => {
  it('shows the connected database, partitioned tables, partitions and triggers', async () => {
    mock();
    const { container } = render(<ObjectExplorer sessionId={SID} database="sales" onInsert={() => {}} />);
    expect(screen.getByText('sales')).toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: 'shop' }));
    expect(await screen.findByText('Partitioned tables')).toBeInTheDocument();
    const partition = screen.getByText('events_2026').closest('li');
    expect(within(partition).getByText('partition')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'orders' }));
    expect(await screen.findByText('Triggers')).toBeInTheDocument();
    const off = screen.getByText('orders_off').closest('li');
    expect(within(off).getByText('disabled')).toBeInTheDocument();
    expect(off).toHaveAttribute('title', expect.stringContaining('AFTER INSERT'));
    expect(within(screen.getByText('orders_touch').closest('li')).queryByText('disabled')).toBeNull();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('lists extensions when asked, and says when there are none or they failed to load', async () => {
    const seen = mock();
    const { unmount } = render(<ObjectExplorer sessionId={SID} onInsert={() => {}} />);
    await screen.findByRole('button', { name: 'shop' });
    expect(seen).not.toContain('extensions'); // loaded lazily
    await userEvent.click(screen.getByRole('button', { name: 'Extensions' }));
    expect(await screen.findByText('plpgsql')).toBeInTheDocument();
    expect(screen.getByText('1.0 · pg_catalog')).toBeInTheDocument();
    unmount();
    mock({ extensions: [] });
    const r2 = render(<ObjectExplorer sessionId={SID} onInsert={() => {}} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Extensions' }));
    expect(await screen.findByText('No extensions installed.')).toBeInTheDocument();
    r2.unmount();
    mock({ extensionsError: envelope(409, 'COMPANION_BUSY', 'busy') });
    render(<ObjectExplorer sessionId={SID} onInsert={() => {}} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Extensions' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('busy loading other information');
  });

  it('offers the other databases to switch to', async () => {
    mock();
    const onSwitch = vi.fn();
    const databases = [{ name: 'sales' }, { name: 'archive' }];
    const { rerender } = render(<ObjectExplorer sessionId={SID} database="sales" databases={databases} onSwitch={onSwitch} onInsert={() => {}} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Other databases' }));
    expect(screen.queryByRole('button', { name: 'Switch to sales' })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Switch to archive' }));
    expect(onSwitch).toHaveBeenCalledWith('archive');
    rerender(<ObjectExplorer sessionId={SID} database="sales" databases={databases} onSwitch={null} onInsert={() => {}} />);
    expect(screen.getByText('archive')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Switch to archive' })).toBeNull();
    rerender(<ObjectExplorer sessionId={SID} database="sales" databases={[{ name: 'sales' }]} onInsert={() => {}} />);
    expect(screen.queryByRole('button', { name: 'Other databases' })).toBeNull();
  });

  it('reloads when the workspace says objects changed', async () => {
    const seen = mock();
    const { rerender } = render(<ObjectExplorer sessionId={SID} refreshKey={0} onInsert={() => {}} />);
    await screen.findByRole('button', { name: 'shop' });
    expect(seen.filter((s) => s === 'schemas')).toHaveLength(1);
    rerender(<ObjectExplorer sessionId={SID} refreshKey={1} onInsert={() => {}} />);
    await waitFor(() => expect(seen.filter((s) => s === 'schemas')).toHaveLength(2));
  });
});

describe('object explorer (admin Phase 3)', () => {
  function mock3({ definitionError = null } = {}) {
    const asked = [];
    server.use(
      http.get(`/api/metadata/${SID}/schemas`, () => HttpResponse.json({ schemas: [{ name: 'shop', owner: 'o', accessible: true }], loadedAt: 'x' })),
      http.get(`/api/metadata/${SID}/objects`, () =>
        HttpResponse.json({
          objects: [
            { kind: 'table', name: 'orders', accessible: true },
            { kind: 'table', name: 'customers', accessible: true },
            { kind: 'table', name: 'secret', accessible: false },
            { kind: 'function', name: 'f', arguments: 'a integer', accessible: true },
          ],
          loadedAt: 'x',
        }),
      ),
      http.get(`/api/metadata/${SID}/relation`, () =>
        HttpResponse.json({
          schema: 'shop', name: 'orders', kind: 'table', accessible: true, owner: 'shop_owner', myPrivileges: ['SELECT', 'INSERT'],
          columns: [], indexes: [], constraints: [], triggers: [],
          grants: [
            { grantee: 'alice', grantor: 'shop_owner', privilege: 'SELECT', grantable: false },
            { grantee: 'alice', grantor: 'shop_owner', privilege: 'INSERT', grantable: true },
          ],
        }),
      ),
      http.get(`/api/metadata/${SID}/definition`, ({ request }) => {
        asked.push(Object.fromEntries(new URL(request.url).searchParams));
        return definitionError || HttpResponse.json({ definition: 'CREATE FUNCTION shop.f(a integer) …;\n', loadedAt: 'x' });
      }),
    );
    return asked;
  }

  it('filters the objects of an opened schema by name', async () => {
    mock3();
    render(<ObjectExplorer sessionId={SID} onInsert={() => {}} />);
    await userEvent.click(await screen.findByRole('button', { name: 'shop' }));
    await screen.findByRole('button', { name: 'orders' });
    await userEvent.type(screen.getByRole('searchbox', { name: 'Filter objects' }), 'CUST');
    expect(screen.getByRole('button', { name: 'customers' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'orders' })).toBeNull();
    await userEvent.clear(screen.getByRole('searchbox', { name: 'Filter objects' }));
    await userEvent.type(screen.getByRole('searchbox', { name: 'Filter objects' }), 'nothing');
    expect(screen.getByText('No objects match the filter.')).toBeInTheDocument();
  });

  it('shows the owner, your privileges and the grants of a table', async () => {
    mock3();
    const { container } = render(<ObjectExplorer sessionId={SID} onInsert={() => {}} onOpen={() => {}} />);
    await userEvent.click(await screen.findByRole('button', { name: 'shop' }));
    await userEvent.click(await screen.findByRole('button', { name: 'orders' }));
    expect(await screen.findByText('shop_owner')).toBeInTheDocument();
    expect(screen.getByText('SELECT, INSERT')).toBeInTheDocument();
    const grants = screen.getByRole('list', { name: 'Privileges on shop.orders' });
    expect(within(grants).getByText('SELECT, INSERT (with grant option)')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('opens a definition in a new tab, only for objects you can use, and reports a failure', async () => {
    const asked = mock3();
    const onOpen = vi.fn();
    render(<ObjectExplorer sessionId={SID} onInsert={() => {}} onOpen={onOpen} />);
    await userEvent.click(await screen.findByRole('button', { name: 'shop' }));
    await screen.findByRole('button', { name: 'orders' });
    expect(screen.queryByRole('button', { name: 'Open definition of shop.secret' })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Open definition of shop.f' }));
    await waitFor(() => expect(onOpen).toHaveBeenCalledWith('CREATE FUNCTION shop.f(a integer) …;\n', 'f.sql'));
    expect(asked[0]).toEqual({ kind: 'function', schema: 'shop', name: 'f', arguments: 'a integer' });
  });

  it('says why a definition could not be loaded', async () => {
    mock3({ definitionError: envelope(409, 'COMPANION_BUSY', 'busy') });
    const onOpen = vi.fn();
    render(<ObjectExplorer sessionId={SID} onInsert={() => {}} onOpen={onOpen} />);
    await userEvent.click(await screen.findByRole('button', { name: 'shop' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Open definition of shop.orders' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('busy loading other information');
    expect(onOpen).not.toHaveBeenCalled();
  });
});
