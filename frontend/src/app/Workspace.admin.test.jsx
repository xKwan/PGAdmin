import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse, server } from '../mocks/server.js';
import Workspace from './Workspace.jsx';

const SESSION = {
  id: 'AbCdEfGhIjKlMnOpQrStUv',
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
const AT = '2026-10-19T08:00:00Z';

function doc(extra) {
  return { executionId: 'e1', statementClass: 'select', kind: 'command', transaction: 'IDLE', durationMs: 1, retrievedAt: AT, results: [], ...extra };
}

/** Discovery, open/switch, and a counter of schema loads (the explorer). */
function mockServer() {
  const calls = { schemas: 0, opens: [] };
  server.use(
    http.post('/api/profiles/external/databases', () =>
      HttpResponse.json({ databases: [{ name: 'sales', owner: 'alice', owned: true }, { name: 'archive', owner: 'bob', owned: false }] }),
    ),
    http.post('/api/db-sessions/external', async ({ request }) => {
      const body = await request.json();
      calls.opens.push(body);
      return HttpResponse.json({ ...SESSION, id: `S${calls.opens.length}xxxxxxxxxxxxxxxxxxxx`, database: body.database }, { status: 201 });
    }),
    http.get('/api/metadata/:id/schemas', () => {
      calls.schemas += 1;
      return HttpResponse.json({ schemas: [], loadedAt: AT });
    }),
  );
  return calls;
}

async function connect(props = {}) {
  const onBack = vi.fn();
  render(<Workspace profile="external" executionEnabled onBack={onBack} user={USER} {...props} />);
  await userEvent.click(await screen.findByRole('button', { name: /^sales/ }));
  await screen.findByRole('button', { name: 'Switch database' }); // the session bar: connected
  await screen.findByRole('textbox', { name: 'SQL editor' }); // the editor chunk is loaded lazily
  return { onBack };
}

const sessionPath = (n = 1) => `/api/db-sessions/S${n}xxxxxxxxxxxxxxxxxxxx`;

describe('Workspace editor (admin Phase 2)', () => {
  it('the editor is there before connecting; nothing can run until a session is open', async () => {
    mockServer();
    render(<Workspace profile="external" executionEnabled onBack={() => {}} user={USER} />);
    expect(await screen.findByRole('textbox', { name: 'SQL editor' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Run' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Run script' })).toBeDisabled();
    expect(screen.getByText('Connect to a database to run SQL')).toBeInTheDocument();
  });

  it('Run script sends the whole tab, shows each statement, and records it in the history', async () => {
    const calls = mockServer();
    const sent = [];
    server.use(
      http.post(`${sessionPath()}/script`, async ({ request }) => {
        sent.push(await request.json());
        return HttpResponse.json({
          kind: 'script', scriptId: 's', statementClass: 'script', total: 2, completed: 2, transaction: 'IDLE', durationMs: 4, retrievedAt: AT,
          statements: [
            { index: 0, offset: 0, length: 8, statementClass: 'select', executionId: 'a', kind: 'command', results: [{ kind: 'command' }], durationMs: 1 },
            { index: 1, offset: 10, length: 8, statementClass: 'select', executionId: 'b', kind: 'command', results: [{ kind: 'command' }], durationMs: 1 },
          ],
        });
      }),
    );
    await connect();
    const editor = await screen.findByRole('textbox', { name: 'SQL editor' });
    await userEvent.click(editor);
    await userEvent.keyboard('SELECT 1;{Enter}SELECT 2');
    await userEvent.click(screen.getByRole('button', { name: 'Run script' }));
    expect(await screen.findByText('All 2 statements completed.')).toBeInTheDocument();
    expect(sent).toEqual([{ sql: 'SELECT 1;\nSELECT 2' }]);
    await userEvent.click(screen.getByRole('button', { name: 'Show statement 2 in the editor' }));
    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: 'SQL editor' }));

    await userEvent.click(screen.getByRole('tab', { name: 'History (1)' }));
    const item = within(screen.getByRole('region', { name: 'Query history' })).getByRole('listitem');
    expect(within(item).getByText('Script (2)')).toBeInTheDocument();
    expect(within(item).getByText('sales')).toBeInTheDocument();
    await userEvent.click(within(item).getByRole('button', { name: 'Open in a new tab' }));
    expect(screen.getByRole('button', { name: 'Query 2' })).toHaveAttribute('aria-current', 'true');
    expect(await screen.findByRole('textbox', { name: 'SQL editor' })).toHaveTextContent('SELECT 1;SELECT 2');
    expect(calls.opens).toHaveLength(1);
  });

  it('refreshes the explorer once a change to objects is committed', async () => {
    const calls = mockServer();
    let next = [];
    server.use(http.post(`${sessionPath()}/execute`, () => HttpResponse.json(next.shift())));
    server.use(http.post(`${sessionPath()}/transaction`, () => HttpResponse.json(next.shift())));
    await connect();
    await waitFor(() => expect(calls.schemas).toBe(1));
    next = [doc({ statementClass: 'ddl' })];
    await userEvent.click(screen.getByRole('button', { name: 'Run' }));
    await waitFor(() => expect(calls.schemas).toBe(2)); // auto-committed DDL
    next = [doc({ statementClass: 'ddl', transaction: 'OPEN' })];
    await userEvent.click(screen.getByRole('button', { name: 'Run' }));
    await screen.findByRole('button', { name: 'Commit' });
    expect(calls.schemas).toBe(2); // not yet committed: the explorer shows committed objects only
    next = [doc({ statementClass: 'transaction', transaction: 'IDLE' })];
    await userEvent.click(screen.getByRole('button', { name: 'Commit' }));
    await waitFor(() => expect(calls.schemas).toBe(3));
    next = [doc({ statementClass: 'select', kind: 'rows', results: [{ kind: 'rows', columns: [], rows: [], rowCount: 0, truncated: false }] })];
    await userEvent.click(screen.getByRole('button', { name: 'Run' }));
    await screen.findByText('0 rows');
    expect(calls.schemas).toBe(3);
  });

  it('asks before leaving with unsaved changes, and the browser is asked to warn', async () => {
    mockServer();
    const onBack = vi.fn();
    render(<Workspace profile="external" executionEnabled onBack={onBack} user={USER} />);
    const editor = await screen.findByRole('textbox', { name: 'SQL editor' });
    const quiet = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(quiet);
    expect(quiet.defaultPrevented).toBe(false);
    await userEvent.click(editor);
    await userEvent.keyboard('SELECT 1');
    const unload = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(unload);
    expect(unload.defaultPrevented).toBe(true);
    await userEvent.click(screen.getByRole('button', { name: '← Servers' }));
    expect(screen.getByRole('dialog', { name: 'Leave with unsaved changes?' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onBack).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: '← Servers' }));
    await userEvent.click(screen.getByRole('button', { name: 'Leave and discard changes' }));
    expect(onBack).toHaveBeenCalled();
  });

  it('leaves at once without unsaved changes', async () => {
    mockServer();
    const onBack = vi.fn();
    render(<Workspace profile="external" executionEnabled onBack={onBack} user={USER} />);
    await screen.findByRole('textbox', { name: 'SQL editor' });
    await userEvent.click(screen.getByRole('button', { name: '← Servers' }));
    expect(onBack).toHaveBeenCalled();
  });

  it('switches database from the object explorer through the same safe switch', async () => {
    const calls = mockServer();
    await connect();
    await userEvent.click(await screen.findByRole('button', { name: 'Other databases' }));
    await userEvent.click(screen.getByRole('button', { name: 'Switch to archive' }));
    await waitFor(() => expect(calls.opens).toHaveLength(2));
    expect(calls.opens[1]).toEqual({ database: 'archive', replaces: 'S1xxxxxxxxxxxxxxxxxxxx' });
    expect(await screen.findByText('archive', { selector: '.session-db' })).toBeInTheDocument();
  });

  it('a refused statement is recorded in the history too', async () => {
    mockServer();
    server.use(
      http.post(`${sessionPath()}/execute`, () => HttpResponse.json({ error: { code: 'SYNTAX_ERROR', message: 'Syntax error', requestId: 'r' } }, { status: 400 })),
    );
    await connect();
    await userEvent.click(screen.getByRole('button', { name: 'Run' }));
    await screen.findByText('Not run');
    await userEvent.click(screen.getByRole('tab', { name: 'History (1)' }));
    const item = within(screen.getByRole('region', { name: 'Query history' })).getByRole('listitem');
    expect(within(item).getByText('Not run')).toBeInTheDocument();
    await userEvent.click(within(item).getByRole('button', { name: 'Insert' }));
    await userEvent.click(screen.getByRole('button', { name: 'Clear history' }));
    expect(screen.getByRole('tab', { name: 'History (0)' })).toBeInTheDocument();
  });
});
