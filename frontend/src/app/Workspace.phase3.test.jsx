import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse, server } from '../mocks/server.js';
import Workspace from './Workspace.jsx';

const SESSION = {
  id: 'S1xxxxxxxxxxxxxxxxxxxx',
  profile: 'external',
  database: 'sales',
  role: 'alice',
  serverVersion: '18.6',
  backendPid: 4242,
  companion: true,
  state: 'OPEN',
  transaction: 'IDLE',
};
const USER = { account: 'CORP\\alice', sid: 'S-1-5-21-1-2-3-1001', dataAdmin: false, authMechanism: 'Kerberos' };
const AT = '2026-10-20T08:00:00Z';
const PLAN = JSON.stringify([{ Plan: { 'Node Type': 'Seq Scan', 'Relation Name': 'orders', 'Startup Cost': 0, 'Total Cost': 20, 'Plan Rows': 1000 }, 'Planning Time': 0.1 }]);

function mockServer() {
  const sent = [];
  server.use(
    http.post('/api/profiles/external/databases', () => HttpResponse.json({ databases: [{ name: 'sales', owner: 'alice', owned: true }] })),
    http.post('/api/db-sessions/external', () => HttpResponse.json(SESSION, { status: 201 })),
    http.get('/api/metadata/:id/schemas', () => HttpResponse.json({ schemas: [{ name: 'shop', owner: 'o', accessible: true }], loadedAt: AT })),
    http.get('/api/metadata/:id/objects', () => HttpResponse.json({ objects: [{ kind: 'view', name: 'v_orders', accessible: true }], loadedAt: AT })),
    http.get('/api/metadata/:id/definition', ({ request }) => {
      const q = new URL(request.url).searchParams;
      return HttpResponse.json({ definition: `CREATE OR REPLACE VIEW shop.${q.get('name')} AS\n SELECT 1;\n`, kind: q.get('kind'), schema: q.get('schema'), name: q.get('name'), loadedAt: AT });
    }),
    http.get('/api/metadata/:id/activity', () => HttpResponse.json({ activity: { sessions: [{ pid: 4242, user: 'alice', visible: true, state: 'idle', query: 'SELECT 1', mine: 'session' }], locks: [] }, loadedAt: AT })),
    http.post(`/api/db-sessions/${SESSION.id}/execute`, async ({ request }) => {
      const body = await request.json();
      sent.push(body);
      if (/ANALYZE/.test(body.sql) && !body.confirmed) {
        return HttpResponse.json({ error: { code: 'CONFIRMATION_REQUIRED', message: 'confirm', reasons: ['explain_analyze'] } }, { status: 409 });
      }
      return HttpResponse.json({
        executionId: 'e1', statementClass: 'explain', kind: 'rows', transaction: 'IDLE', durationMs: 2, retrievedAt: AT,
        results: [{ kind: 'rows', columns: [{ name: 'QUERY PLAN', type: 'json' }], rows: [[PLAN]], rowCount: 1, truncated: false }],
      });
    }),
  );
  return sent;
}

async function connect() {
  render(<Workspace profile="external" executionEnabled onBack={vi.fn()} user={USER} />);
  await userEvent.click(await screen.findByRole('button', { name: /^sales/ }));
  await screen.findByRole('button', { name: 'Switch database' });
  return screen.findByRole('textbox', { name: 'SQL editor' });
}

describe('Workspace administration (admin Phase 3)', () => {
  it('Explain shows the plan of the statement at the cursor without running it', async () => {
    const sent = mockServer();
    const editor = await connect();
    await userEvent.click(editor);
    await userEvent.keyboard('SELECT * FROM orders');
    await userEvent.click(screen.getByRole('button', { name: 'Explain' }));
    expect(await screen.findByRole('list', { name: 'Query plan' })).toBeInTheDocument();
    expect(screen.getByText('Seq Scan on orders')).toBeInTheDocument();
    expect(sent).toEqual([{ sql: 'EXPLAIN (FORMAT JSON) SELECT * FROM orders' }]);
  });

  it('Explain analyze warns that the statement runs, and runs it only when confirmed', async () => {
    const sent = mockServer();
    const editor = await connect();
    await userEvent.click(editor);
    await userEvent.keyboard('DELETE FROM orders WHERE id = 1');
    await userEvent.click(screen.getByRole('button', { name: 'Explain analyze' }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('runs the statement to measure it');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Run it' }));
    await screen.findByRole('list', { name: 'Query plan' });
    expect(sent.map((b) => b.confirmed ?? false)).toEqual([false, true]);
    expect(sent[1].sql).toBe('EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) DELETE FROM orders WHERE id = 1');
  });

  it('an EXPLAIN typed by the user is sent as written', async () => {
    const sent = mockServer();
    const editor = await connect();
    await userEvent.click(editor);
    await userEvent.keyboard('explain select 1');
    await userEvent.click(screen.getByRole('button', { name: 'Explain' }));
    await screen.findByRole('list', { name: 'Query plan' });
    expect(sent[0].sql).toBe('explain select 1');
  });

  it('opens an object definition in a new editor tab, and shows the server activity', async () => {
    mockServer();
    await connect();
    await userEvent.click(await screen.findByRole('button', { name: 'shop' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Open definition of shop.v_orders' }));
    expect(await screen.findByRole('button', { name: 'v_orders.sql' })).toHaveAttribute('aria-current', 'true');
    expect(await screen.findByRole('textbox', { name: 'SQL editor' })).toHaveTextContent('CREATE OR REPLACE VIEW shop.v_orders AS');
    await userEvent.click(screen.getByRole('tab', { name: 'Activity' }));
    expect(await screen.findByText('this session')).toBeInTheDocument();
  });
});
