import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { http, HttpResponse, server } from '../../mocks/server.js';
import { axeViolations } from '../../test/a11y.js';
import { ActivityPanel, DatabasePanel, RolesPanel, since } from './AdminPanels.jsx';

const SID = 'AbCdEfGhIjKlMnOpQrStUv';
const LOADED = '2026-10-20T08:00:30Z';

function activity(calls) {
  server.use(
    http.get(`/api/metadata/${SID}/activity`, () => {
      calls.push('activity');
      return HttpResponse.json({
        activity: {
          sessions: [
            { pid: 101, user: 'ada', database: 'sales', application: 'pgweb', visible: true, state: 'active', waitEventType: 'Lock', waitEvent: 'relation', queryStart: '2026-10-20T08:00:00Z', query: 'SELECT count(*) FROM shop.orders', blockedBy: [202], mine: 'session' },
            { pid: 102, user: 'ada', database: 'sales', application: 'pgweb-companion', visible: true, state: 'idle', query: 'SELECT 1', mine: 'companion' },
            { pid: 202, user: 'postgres', database: 'sales', application: 'psql', visible: false },
          ],
          locks: [
            { pid: 202, type: 'relation', mode: 'AccessExclusiveLock', granted: true, relation: 'shop.orders', database: 'sales' },
            { pid: 101, type: 'relation', mode: 'AccessShareLock', granted: false, waitStart: '2026-10-20T08:00:01Z', relation: 'shop.orders', database: 'sales' },
          ],
          sessionsTruncated: true,
        },
        loadedAt: LOADED,
      });
    }),
  );
}

describe('administration panels (admin Phase 3)', () => {
  it('shows sessions, who blocks whom and the locks, and reads again only on Refresh', async () => {
    const calls = [];
    activity(calls);
    const { container } = render(<ActivityPanel sessionId={SID} />);
    const mine = (await screen.findByText('SELECT count(*) FROM shop.orders')).closest('tr');
    expect(within(mine).getByText('this session')).toBeInTheDocument();
    expect(within(mine).getByText('blocked by 202')).toBeInTheDocument();
    expect(within(mine).getByText('30 s')).toBeInTheDocument();
    const sessions = screen.getByRole('table', { name: /^Sessions/ });
    expect(within(within(sessions).getByText('202', { selector: 'td' }).closest('tr')).getByText('not visible to your role')).toBeInTheDocument();
    expect(screen.getByText(/Only the first 500 sessions/)).toBeInTheDocument();
    expect(screen.getByText(/waiting since/)).toBeInTheDocument();
    expect(screen.getByText('held')).toBeInTheDocument();
    expect(calls).toEqual(['activity']);
    await userEvent.click(screen.getByRole('checkbox', { name: 'Hide idle sessions' }));
    expect(screen.queryByText('SELECT 1')).toBeNull();
    expect(screen.getByRole('table', { name: /Sessions \(2\)/ })).toBeInTheDocument(); // the hidden one has no state
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    await screen.findByText('SELECT count(*) FROM shop.orders');
    expect(calls).toEqual(['activity', 'activity']);
  });

  it('says what went wrong and tries again', async () => {
    let fail = true;
    server.use(
      http.get(`/api/metadata/${SID}/activity`, () =>
        fail ? HttpResponse.json({ error: { code: 'COMPANION_BUSY', message: 'busy' } }, { status: 409 }) : HttpResponse.json({ activity: { sessions: [], locks: [] }, loadedAt: LOADED }),
      ),
    );
    render(<ActivityPanel sessionId={SID} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('busy loading other information');
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('table', { name: 'Sessions (0)' })).toBeInTheDocument();
  });

  it('shows the database, your privileges, its grants and the sizes', async () => {
    server.use(
      http.get(`/api/metadata/${SID}/database`, () =>
        HttpResponse.json({
          database: {
            name: 'sales', owner: 'sales_owner', encoding: 'UTF8', collate: 'en_GB.UTF-8', ctype: 'en_GB.UTF-8', connectionLimit: -1, tablespace: 'pg_default',
            sizeBytes: 9 * 1024 * 1024, canCreateSchemas: false, canCreateTemp: true, currentRole: 'ada', serverVersion: '18.1', serverStarted: '2026-10-19T07:00:00Z',
            comment: 'Sales data',
            grants: [
              { grantee: 'PUBLIC', grantor: 'sales_owner', privilege: 'CONNECT', grantable: false },
              { grantee: 'PUBLIC', grantor: 'sales_owner', privilege: 'TEMPORARY', grantable: false },
              { grantee: 'ada', grantor: 'sales_owner', privilege: 'CREATE', grantable: true },
            ],
          },
          loadedAt: LOADED,
        }),
      ),
      http.get(`/api/metadata/${SID}/sizes`, () =>
        HttpResponse.json({
          sizes: {
            databases: [{ name: 'sales', sizeBytes: 9 * 1024 * 1024 }, { name: 'closed' }],
            tables: [
              { schema: 'shop', name: 'orders', kind: 'table', totalBytes: 2048, tableBytes: 1024, indexBytes: 1024, estimatedRows: 1500 },
              { schema: 'shop', name: 'mv', kind: 'materialized_view', totalBytes: 1024, tableBytes: 1024, indexBytes: 0 },
            ],
            tablesLimit: 100,
          },
          loadedAt: LOADED,
        }),
      ),
    );
    const { container } = render(<DatabasePanel sessionId={SID} />);
    expect(await screen.findByText('sales_owner')).toBeInTheDocument();
    expect(screen.getByText('create temporary tables')).toBeInTheDocument();
    expect(screen.getByText('No limit')).toBeInTheDocument();
    expect(screen.getByText('Sales data')).toBeInTheDocument();
    expect(screen.getByText('CONNECT, TEMPORARY')).toBeInTheDocument();
    expect(screen.getByText('CREATE (with grant option)')).toBeInTheDocument();
    const closed = (await screen.findByText('closed')).closest('tr');
    expect(within(closed).getByText('—')).toHaveAttribute('title', 'You may not connect to this database');
    expect(screen.getByText('1,500')).toBeInTheDocument();
    expect(screen.getByText('not analysed')).toBeInTheDocument();
    expect(screen.getByText('(materialized view)')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('lists roles with memberships, filters them and can include the predefined ones', async () => {
    const asked = [];
    server.use(
      http.get(`/api/metadata/${SID}/roles`, ({ request }) => {
        const all = new URL(request.url).searchParams.get('all') === 'true';
        asked.push(all);
        const roles = [
          { name: 'ada', attributes: ['login'], member: true, current: true },
          { name: 'readers', attributes: [], member: true, comment: 'Read access' },
          { name: 'postgres', attributes: ['superuser', 'createrole', 'createdb', 'login', 'replication', 'bypassrls'], member: false },
          { name: 'limited', attributes: ['login'], connectionLimit: 3, member: false },
        ];
        if (all) roles.push({ name: 'pg_read_all_stats', attributes: [], member: false });
        return HttpResponse.json({
          roles: { roles, memberships: [{ role: 'readers', member: 'ada', grantor: 'postgres', admin: true, inherit: true, set: false }] },
          loadedAt: LOADED,
        });
      }),
    );
    const { container } = render(<RolesPanel sessionId={SID} />);
    const ada = (await screen.findByText('ada', { selector: 'td' })).closest('tr');
    expect(within(ada).getByText('you')).toBeInTheDocument();
    expect(within(ada).getByText('readers (admin, no set)')).toBeInTheDocument();
    expect(within(screen.getByText('readers', { selector: 'td' }).closest('tr')).getByText('ada (admin, no set)')).toBeInTheDocument();
    expect(screen.getByText('can log in, 3 connections')).toBeInTheDocument();
    expect(screen.getByText(/superuser, create role, create database/)).toBeInTheDocument();
    expect(screen.queryByText('pg_read_all_stats')).toBeNull();
    await userEvent.type(screen.getByRole('searchbox', { name: 'Filter roles' }), 'READ');
    expect(screen.getByRole('table', { name: 'Roles (1)' })).toBeInTheDocument();
    await userEvent.clear(screen.getByRole('searchbox', { name: 'Filter roles' }));
    await userEvent.click(screen.getByRole('checkbox', { name: /predefined roles/ }));
    expect(await screen.findByText('pg_read_all_stats')).toBeInTheDocument();
    expect(asked).toEqual([false, true]);
    expect(await axeViolations(container)).toEqual([]);
  });

  it('says how long ago something started', () => {
    expect(since('2026-10-20T08:00:00Z', '2026-10-20T08:00:05Z')).toBe('5 s');
    expect(since('2026-10-20T08:00:00Z', '2026-10-20T08:10:00Z')).toBe('10 min');
    expect(since('2026-10-20T08:00:00Z', '2026-10-20T11:00:00Z')).toBe('3 h');
    expect(since(null, '2026-10-20T11:00:00Z')).toBe('');
  });
});
