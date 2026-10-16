import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { describeConnectionError } from '../../api/dbsessions.js';
import Workspace from '../../app/Workspace.jsx';
import { envelope, http, HttpResponse, server } from '../../mocks/server.js';
import { axeViolations } from '../../test/a11y.js';
import { randomText, seededRandom } from '../../test/random.js';
import ConnectPanel from './ConnectPanel.jsx';
import SessionBar from './SessionBar.jsx';

const session = (over = {}) => ({
  id: 'AbCdEfGhIjKlMnOpQrStUv',
  profile: 'external',
  database: 'sales',
  role: 'alice',
  serverVersion: '18.6',
  backendPid: 4242,
  companion: true,
  state: 'OPEN',
  transaction: 'IDLE',
  ...over,
});

afterEach(() => vi.useRealTimers());

describe('ConnectPanel', () => {
  it('lists the databases the server allows and connects to one', async () => {
    const rnd = seededRandom('db-names');
    const names = Array.from({ length: 1 + Math.floor(rnd() * 5) }, (_, i) => `db_${i}_${randomText(rnd, 6)}`);
    let opened = null;
    server.use(
      http.post('/api/profiles/external/databases', () =>
        HttpResponse.json({ databases: names.map((n, i) => ({ name: n, owner: 'x', owned: i === 0 })) }),
      ),
      http.post('/api/db-sessions/external', async ({ request }) => {
        opened = await request.json();
        return HttpResponse.json(session({ database: opened.database }), { status: 201 });
      }),
    );
    const onConnected = vi.fn();
    const { container } = render(<ConnectPanel profile="external" onConnected={onConnected} />);
    expect(screen.getByText(/own PostgreSQL role/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/password/i)).toBeNull(); // no credentials anywhere (ADR-0002)
    await userEvent.click(screen.getByRole('button', { name: 'Show my databases' }));
    const list = await screen.findByRole('list', { name: 'Databases' });
    expect(list.querySelectorAll('li')).toHaveLength(names.length);
    expect(screen.getByText('owner')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    // Match on raw text: accessible names collapse whitespace, and the random names may contain tabs.
    const first = Array.from(list.querySelectorAll('button')).find((b) => b.textContent.startsWith(names[0]));
    expect(first).toBeDefined();
    await userEvent.click(first);
    await waitFor(() => expect(onConnected).toHaveBeenCalledWith(expect.objectContaining({ database: names[0] })));
    expect(opened).toEqual({ database: names[0] });
  });

  it('explains an empty list and connection failures, with retry', async () => {
    let calls = 0;
    server.use(
      http.post('/api/profiles/external/databases', () => {
        calls += 1;
        return calls === 1 ? HttpResponse.json({ databases: [] }) : HttpResponse.json({ databases: [{ name: 'only', owner: 'x', owned: false }] });
      }),
      http.post('/api/db-sessions/external', () => envelope(422, 'CONNECT_FAILED', 'raw message')),
    );
    render(<ConnectPanel profile="external" onConnected={() => {}} />);
    await userEvent.click(screen.getByRole('button', { name: 'Show my databases' }));
    expect(await screen.findByText('No databases')).toBeInTheDocument();
    render(<ConnectPanel profile="external" onConnected={() => {}} />);
    await userEvent.click(screen.getAllByRole('button', { name: 'Show my databases' })[0]);
    await userEvent.click(await screen.findByRole('button', { name: /only/ }));
    expect(await screen.findByText(/Could not connect to the database/)).toBeInTheDocument();
    expect(screen.getByText('Code: CONNECT_FAILED')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('list', { name: 'Databases' })).toBeInTheDocument();
  });

  it('at the session limit, lists open sessions so one can be disconnected', async () => {
    let open = [session({ id: 'S1aaaaaaaaaaaaaaaaaaaa', database: 'sales' }), session({ id: 'S2bbbbbbbbbbbbbbbbbbbb', database: 'hr', createdAt: '2026-10-16T08:00:00Z' })];
    let listFails = true;
    const closed = [];
    server.use(
      http.post('/api/profiles/external/databases', () => HttpResponse.json({ databases: [{ name: 'sales', owner: 'x', owned: true }] })),
      http.post('/api/db-sessions/external', () => (open.length >= 2 ? envelope(409, 'SESSION_LIMIT', 'You already have 2 open database sessions; close one first.') : HttpResponse.json(session(), { status: 201 }))),
      http.get('/api/db-sessions', () => {
        if (listFails) {
          listFails = false;
          return envelope(503, 'UNAVAILABLE', 'busy');
        }
        return HttpResponse.json({ sessions: open });
      }),
      http.delete('/api/db-sessions/:id', ({ params }) => {
        closed.push(params.id);
        open = open.filter((s) => s.id !== params.id);
        return params.id.startsWith('S2') ? envelope(404, 'NOT_FOUND', 'Not found') : new HttpResponse(null, { status: 204 });
      }),
    );
    const onConnected = vi.fn();
    render(<ConnectPanel profile="external" onConnected={onConnected} />);
    await userEvent.click(screen.getByRole('button', { name: 'Show my databases' }));
    await userEvent.click(await screen.findByRole('button', { name: /sales/ }));
    expect(await screen.findByText(/already have 2 open database sessions/)).toBeInTheDocument();
    expect(await screen.findByText('Could not list your sessions')).toBeInTheDocument();
    await userEvent.click(screen.getAllByRole('button', { name: /Try again|Retry/ }).at(-1));
    const list = await screen.findByRole('region', { name: 'Your open sessions' });
    expect(within(list).getAllByText(/^since /)).toHaveLength(2);
    await userEvent.click(within(list).getByRole('button', { name: 'Disconnect hr' })); // already gone (404): fine
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Disconnect hr' })).toBeNull());
    expect(screen.getByRole('button', { name: 'Disconnect sales' })).toBeInTheDocument();
    expect(closed).toEqual(['S2bbbbbbbbbbbbbbbbbbbb']);
    await userEvent.click(within(screen.getByRole('region', { name: 'Your open sessions' })).getByRole('button', { name: 'Show my databases' }));
    await userEvent.click(await screen.findByRole('button', { name: /sales/ }));
    await waitFor(() => expect(onConnected).toHaveBeenCalled());
  });

  it('describes every connection error code', () => {
    expect(describeConnectionError({ code: 'SUPERUSER_REFUSED' })).toMatch(/Superuser/);
    expect(describeConnectionError({ code: 'SESSION_LIMIT', message: 'limit msg' })).toBe('limit msg');
    expect(describeConnectionError({ code: 'RATE_LIMITED', message: 'slow down' })).toBe('slow down');
    expect(describeConnectionError({ code: 'SERVER_BUSY', message: 'busy' })).toBe('busy');
    expect(describeConnectionError({ code: 'DELEGATION_HANDSHAKE' })).toMatch(/full host name/);
    expect(describeConnectionError({ code: 'FORBIDDEN' })).toMatch(/not allowed/);
    expect(describeConnectionError({ code: 'X', message: 'other' })).toBe('other');
    expect(describeConnectionError(null)).toBe('The connection failed.');
  });

  it('shows a delegation failure without reloading the page', async () => {
    server.use(http.post('/api/profiles/external/databases', () => envelope(401, 'DELEGATION_HANDSHAKE', 'Windows sign-in is needed')));
    render(<ConnectPanel profile="external" onConnected={() => {}} />);
    await userEvent.click(screen.getByRole('button', { name: 'Show my databases' }));
    expect(await screen.findByText(/did not complete/)).toBeInTheDocument();
  });
});

describe('SessionBar', () => {
  it('shows the session, warns when limited, and disconnects', async () => {
    let deleted = null;
    server.use(
      http.delete('/api/db-sessions/:id', ({ params }) => {
        deleted = params.id;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const onClosed = vi.fn();
    const { rerender, container } = render(<SessionBar session={session()} onClosed={onClosed} />);
    expect(screen.getByLabelText('Database session')).toHaveTextContent('sales');
    expect(screen.getByText('alice')).toBeInTheDocument();
    expect(screen.getByText(/PID 4242/)).toBeInTheDocument();
    expect(screen.queryByText('Limited')).toBeNull();
    expect(await axeViolations(container)).toEqual([]);
    rerender(<SessionBar session={session({ companion: false })} onClosed={onClosed} />);
    expect(screen.getByText('Limited')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Disconnect' }));
    await waitFor(() => expect(onClosed).toHaveBeenCalledOnce());
    expect(deleted).toBe('AbCdEfGhIjKlMnOpQrStUv');
  });

  it('still returns to the server list when the session is already gone', async () => {
    server.use(http.delete('/api/db-sessions/:id', () => envelope(404, 'NOT_FOUND', 'Not found')));
    const onClosed = vi.fn();
    render(<SessionBar session={session()} onClosed={onClosed} />);
    await userEvent.click(screen.getByRole('button', { name: 'Disconnect' }));
    await waitFor(() => expect(onClosed).toHaveBeenCalledOnce());
  });

  it('sends heartbeats and reports a lost session', async () => {
    let beats = 0;
    server.use(
      http.post('/api/db-sessions/:id/heartbeat', () => {
        beats += 1;
        return beats < 3 ? HttpResponse.json({ state: 'OPEN', transaction: 'IDLE' }) : envelope(410, 'SESSION_LOST', 'The connection to the database was lost.');
      }),
    );
    const onClosed = vi.fn();
    render(<SessionBar session={session()} onClosed={onClosed} heartbeatMs={20} />);
    expect(await screen.findByText('The connection to the database was lost.', {}, { timeout: 3000 })).toBeInTheDocument();
    expect(beats).toBe(3);
    await userEvent.click(screen.getByRole('button', { name: 'Connect again' }));
    expect(onClosed).toHaveBeenCalledOnce();
  });

  it('treats 404 as ended and ignores transient heartbeat errors', async () => {
    let beats = 0;
    server.use(
      http.post('/api/db-sessions/:id/heartbeat', () => {
        beats += 1;
        return beats === 1 ? envelope(503, 'UNAVAILABLE', 'busy') : envelope(404, 'NOT_FOUND', 'Not found');
      }),
    );
    render(<SessionBar session={session()} onClosed={() => {}} heartbeatMs={20} />);
    expect(await screen.findByText('The database session has ended.', {}, { timeout: 3000 })).toBeInTheDocument();
    expect(beats).toBe(2);
  });
});

describe('Workspace with connections', () => {
  it('connects and disconnects within the workspace', async () => {
    server.use(
      http.post('/api/profiles/internal/databases', () => HttpResponse.json({ databases: [{ name: 'ops', owner: 'dba', owned: true }] })),
      http.post('/api/db-sessions/internal', () => HttpResponse.json(session({ profile: 'internal', database: 'ops' }), { status: 201 })),
      http.delete('/api/db-sessions/:id', () => new HttpResponse(null, { status: 204 })),
    );
    render(<Workspace profile="internal" onBack={() => {}} />);
    expect(screen.getByText('Not connected', { selector: '.badge' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Show my databases' }));
    await userEvent.click(await screen.findByRole('button', { name: /ops/ }));
    expect(await screen.findByLabelText('Database session')).toHaveTextContent('ops');
    expect(screen.getAllByText(/arrives in a later version/, { selector: '.empty-body' })).toHaveLength(1); // object explorer (Phase 10)
    expect(await screen.findByRole('textbox', { name: 'SQL editor' })).toBeInTheDocument();
    expect(screen.getByText('Running SQL is switched off by the administrator')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Run' })).toBeDisabled();
    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: 'Disconnect' }));
    });
    expect(await screen.findByRole('button', { name: 'Show my databases' })).toBeInTheDocument();
  });
});
