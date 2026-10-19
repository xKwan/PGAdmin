import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { describeConnectionError } from '../../api/dbsessions.js';
import Workspace from '../../app/Workspace.jsx';
import { envelope, http, HttpResponse, server } from '../../mocks/server.js';
import { axeViolations } from '../../test/a11y.js';
import { randomText, seededRandom } from '../../test/random.js';
import ConnectionStatus, { serverState } from './ConnectionStatus.jsx';
import ConnectPanel from './ConnectPanel.jsx';
import { getLastDatabase, setLastDatabase } from './lastDatabase.js';
import SessionBar, { LostSession } from './SessionBar.jsx';

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

/** A discovery result as useServerDiscovery returns it. */
const discovery = (over = {}) => ({ status: 'reachable', databases: [], checkedAt: null, error: null, refreshing: false, refresh: vi.fn(), ...over });
const dbs = (...names) => names.map((name, i) => ({ name, owner: 'x', owned: i === 0 }));
const USER = { account: 'CORP\\alice', sid: 'S-1-5-21-1-2-3-1001', dataAdmin: true, authMechanism: 'Kerberos' };

afterEach(() => {
  vi.useRealTimers();
  window.localStorage.clear();
});

describe('ConnectPanel', () => {
  it('lists the discovered databases and connects to one, without asking for credentials', async () => {
    const rnd = seededRandom('db-names');
    const names = Array.from({ length: 1 + Math.floor(rnd() * 5) }, (_, i) => `db_${i}_${randomText(rnd, 6)}`);
    let opened = null;
    server.use(
      http.post('/api/db-sessions/external', async ({ request }) => {
        opened = await request.json();
        return HttpResponse.json(session({ database: opened.database }), { status: 201 });
      }),
    );
    const onConnected = vi.fn();
    const onConnecting = vi.fn();
    const { container } = render(<ConnectPanel profile="external" discovery={discovery({ databases: dbs(...names) })} onConnected={onConnected} onConnecting={onConnecting} />);
    expect(screen.getByText('No database open')).toBeInTheDocument();
    expect(screen.getByText(/own PostgreSQL role/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/password/i)).toBeNull(); // no credentials anywhere (ADR-0002)
    const list = screen.getByRole('list', { name: 'Databases' });
    expect(list.querySelectorAll('li')).toHaveLength(names.length);
    expect(screen.getByText('owner')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    // Match on raw text: accessible names collapse whitespace, and the random names may contain tabs.
    const first = Array.from(list.querySelectorAll('button')).find((b) => b.textContent.startsWith(names[0]));
    await userEvent.click(first);
    await waitFor(() => expect(onConnected).toHaveBeenCalledWith(expect.objectContaining({ database: names[0] })));
    expect(opened).toEqual({ database: names[0] }); // a plain open: no replace fields
    expect(onConnecting).toHaveBeenCalledWith(names[0]);
  });

  it('offers the last database used, if it is still in the list', async () => {
    server.use(http.post('/api/db-sessions/external', async ({ request }) => HttpResponse.json(session({ database: (await request.json()).database }), { status: 201 })));
    const onConnected = vi.fn();
    const { rerender } = render(<ConnectPanel profile="external" discovery={discovery({ databases: dbs('a', 'hr') })} lastDatabase="hr" onConnected={onConnected} />);
    expect(screen.getByText('last used')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Connect to hr' }));
    await waitFor(() => expect(onConnected).toHaveBeenCalledWith(expect.objectContaining({ database: 'hr' })));
    rerender(<ConnectPanel profile="external" discovery={discovery({ databases: dbs('a') })} lastDatabase="hr" onConnected={onConnected} />);
    expect(screen.queryByRole('button', { name: 'Connect to hr' })).toBeNull();
  });

  it('shows discovery progress, an empty list, and a discovery failure with retry', async () => {
    const refresh = vi.fn();
    const { rerender } = render(<ConnectPanel profile="external" discovery={discovery({ status: 'checking', refreshing: true })} onConnected={() => {}} />);
    expect(screen.getByRole('status')).toHaveTextContent('Finding your databases');
    rerender(<ConnectPanel profile="external" discovery={discovery({ refresh })} onConnected={() => {}} />);
    expect(screen.getByText('No databases')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Refresh list' }));
    expect(refresh).toHaveBeenCalledOnce();
    rerender(<ConnectPanel profile="external" discovery={discovery({ status: 'failed', error: { code: 'DELEGATION_HANDSHAKE', message: 'x' }, refresh })} onConnected={() => {}} />);
    expect(screen.getByText('Could not list your databases')).toBeInTheDocument();
    expect(screen.getByText(/did not complete/)).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Databases' })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it('a failed open names the database, can be retried, and opens nothing', async () => {
    let calls = 0;
    server.use(
      http.post('/api/db-sessions/external', () => {
        calls += 1;
        return calls === 1 ? envelope(422, 'CONNECT_FAILED', 'raw message') : HttpResponse.json(session({ database: 'only' }), { status: 201 });
      }),
    );
    const onConnected = vi.fn();
    const onConnecting = vi.fn();
    render(<ConnectPanel profile="external" discovery={discovery({ databases: dbs('only') })} onConnected={onConnected} onConnecting={onConnecting} />);
    await userEvent.click(screen.getByRole('button', { name: /only/ }));
    expect(await screen.findByText('Could not open only')).toBeInTheDocument();
    expect(screen.getByText(/Could not connect to the database/)).toBeInTheDocument();
    expect(screen.getByText('Code: CONNECT_FAILED')).toBeInTheDocument();
    expect(onConnected).not.toHaveBeenCalled();
    expect(onConnecting).toHaveBeenLastCalledWith(null);
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(onConnected).toHaveBeenCalledOnce());
  });

  it('at the session limit, lists open sessions so one can be disconnected', async () => {
    let open = [session({ id: 'S1aaaaaaaaaaaaaaaaaaaa', database: 'sales' }), session({ id: 'S2bbbbbbbbbbbbbbbbbbbb', database: 'hr', createdAt: '2026-10-16T08:00:00Z' })];
    let listFails = true;
    const closed = [];
    server.use(
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
    const refresh = vi.fn();
    render(<ConnectPanel profile="external" discovery={discovery({ databases: dbs('sales'), refresh })} onConnected={onConnected} />);
    await userEvent.click(screen.getByRole('button', { name: /sales/ }));
    expect(await screen.findByText(/already have 2 open database sessions/)).toBeInTheDocument();
    expect(await screen.findByText('Could not list your sessions')).toBeInTheDocument();
    await userEvent.click(screen.getAllByRole('button', { name: /Try again|Retry/ }).at(-1));
    const list = await screen.findByRole('region', { name: 'Your open sessions' });
    expect(within(list).getAllByText(/^since /)).toHaveLength(2);
    await userEvent.click(within(list).getByRole('button', { name: 'Disconnect hr' })); // already gone (404): fine
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Disconnect hr' })).toBeNull());
    expect(screen.getByRole('button', { name: 'Disconnect sales' })).toBeInTheDocument();
    expect(closed).toEqual(['S2bbbbbbbbbbbbbbbbbbbb']);
    await userEvent.click(within(screen.getByRole('region', { name: 'Your open sessions' })).getByRole('button', { name: 'Back to my databases' }));
    expect(refresh).toHaveBeenCalled();
    await userEvent.click(await screen.findByRole('button', { name: /sales/ }));
    await waitFor(() => expect(onConnected).toHaveBeenCalled());
  });

  it('describes every connection error code', () => {
    expect(describeConnectionError({ code: 'SUPERUSER_REFUSED' })).toMatch(/Superuser/);
    expect(describeConnectionError({ code: 'SESSION_LIMIT', message: 'limit msg' })).toBe('limit msg');
    expect(describeConnectionError({ code: 'RATE_LIMITED', message: 'slow down' })).toBe('slow down');
    expect(describeConnectionError({ code: 'SERVER_BUSY', message: 'busy' })).toBe('busy');
    expect(describeConnectionError({ code: 'TRANSACTION_OPEN', message: 'tx open' })).toBe('tx open');
    expect(describeConnectionError({ code: 'SESSION_BUSY' })).toMatch(/still running/);
    expect(describeConnectionError({ code: 'INVALID_REPLACE' })).toMatch(/same server/);
    expect(describeConnectionError({ code: 'AUDIT_UNAVAILABLE' })).toMatch(/audit service/);
    expect(describeConnectionError({ code: 'NETWORK' })).toMatch(/could not be reached/);
    expect(describeConnectionError({ code: 'DELEGATION_HANDSHAKE' })).toMatch(/full host name/);
    expect(describeConnectionError({ code: 'FORBIDDEN' })).toMatch(/not allowed/);
    expect(describeConnectionError({ code: 'X', message: 'other' })).toBe('other');
    expect(describeConnectionError(null)).toBe('The connection failed.');
  });
});

describe('ConnectionStatus', () => {
  it('maps every discovery outcome to a server state, and an open session is always reachable', () => {
    const failed = (code) => serverState(discovery({ status: 'failed', error: { code } }), false).text;
    expect(serverState(discovery({ status: 'checking' }), false).text).toBe('Checking…');
    expect(serverState(discovery(), false)).toEqual({ tone: 'ok', text: 'Reachable' });
    expect(failed('DELEGATION_HANDSHAKE')).toBe('Windows sign-in incomplete');
    expect(failed('FORBIDDEN')).toBe('Access refused');
    expect(failed('RATE_LIMITED')).toBe('Too many failed attempts');
    expect(failed('AUDIT_UNAVAILABLE')).toBe('Audit service unavailable');
    expect(failed('NETWORK')).toBe('Application server unreachable');
    expect(failed('CONNECT_FAILED')).toBe('Could not connect');
    expect(failed('OTHER')).toBe('Check failed');
    expect(serverState(discovery({ status: 'failed', error: { code: 'NETWORK' } }), true).text).toBe('Reachable');
  });

  it('shows identity, server, database and session separately', async () => {
    const { rerender, container } = render(
      <ConnectionStatus user={USER} serverName="Internal PostgreSQL" discovery={discovery({ checkedAt: new Date('2026-10-19T08:00:00Z') })} phase="none" />,
    );
    const status = screen.getByRole('region', { name: 'Connection status' });
    expect(status).toHaveTextContent('CORP\\alice');
    expect(status).toHaveTextContent('(Kerberos)');
    expect(status).toHaveTextContent('Reachable');
    expect(status).toHaveTextContent('none open');
    expect(status).toHaveTextContent('No session');
    expect(await axeViolations(container)).toEqual([]);
    rerender(<ConnectionStatus serverName="X" discovery={discovery({ status: 'checking' })} phase="opening" target="hr" />);
    expect(status).toHaveTextContent('Opening hr…');
    expect(status).not.toHaveTextContent('Signed in');
    rerender(<ConnectionStatus serverName="X" discovery={discovery()} phase="switching" database="sales" target="hr" />);
    expect(status).toHaveTextContent('Switching to hr…');
    expect(status).toHaveTextContent('sales');
    rerender(<ConnectionStatus serverName="X" discovery={discovery()} phase="lost" />);
    expect(status).toHaveTextContent('Ended');
  });
});

describe('lastDatabase', () => {
  it('remembers only the name, per profile and user, and survives unavailable storage', () => {
    setLastDatabase('internal', 'S-1', 'ops');
    expect(getLastDatabase('internal', 'S-1')).toBe('ops');
    expect(getLastDatabase('internal', 'S-2')).toBeNull();
    expect(getLastDatabase('external', 'S-1')).toBeNull();
    expect(Object.values(window.localStorage)).toEqual(['ops']);
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const spy2 = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(getLastDatabase('internal', 'S-1')).toBeNull();
    expect(() => setLastDatabase('internal', 'S-1', 'x')).not.toThrow();
    spy.mockRestore();
    spy2.mockRestore();
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
    const { rerender, container } = render(<SessionBar session={session()} profile="external" discovery={discovery()} onClosed={onClosed} />);
    expect(screen.getByLabelText('Database session')).toHaveTextContent('sales');
    expect(screen.getByText('alice')).toBeInTheDocument();
    expect(screen.getByText(/PID 4242/)).toBeInTheDocument();
    expect(screen.queryByText('Limited')).toBeNull();
    expect(await axeViolations(container)).toEqual([]);
    rerender(<SessionBar session={session({ companion: false })} profile="external" discovery={discovery()} onClosed={onClosed} />);
    expect(screen.getByText('Limited')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Disconnect' }));
    await waitFor(() => expect(onClosed).toHaveBeenCalledOnce());
    expect(deleted).toBe('AbCdEfGhIjKlMnOpQrStUv');
  });

  it('still returns to the server list when the session is already gone', async () => {
    server.use(http.delete('/api/db-sessions/:id', () => envelope(404, 'NOT_FOUND', 'Not found')));
    const onClosed = vi.fn();
    render(<SessionBar session={session()} profile="external" discovery={discovery()} onClosed={onClosed} />);
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
    const onLost = vi.fn();
    render(<SessionBar session={session()} profile="external" discovery={discovery()} onLost={onLost} heartbeatMs={20} />);
    await waitFor(() => expect(onLost).toHaveBeenCalledWith('The connection to the database was lost.'), { timeout: 3000 });
    expect(beats).toBe(3);
  });

  it('treats 404 as ended and ignores transient heartbeat errors', async () => {
    let beats = 0;
    server.use(
      http.post('/api/db-sessions/:id/heartbeat', () => {
        beats += 1;
        return beats === 1 ? envelope(503, 'UNAVAILABLE', 'busy') : envelope(404, 'NOT_FOUND', 'Not found');
      }),
    );
    const onLost = vi.fn();
    render(<SessionBar session={session()} profile="external" discovery={discovery()} onLost={onLost} heartbeatMs={20} />);
    await waitFor(() => expect(onLost).toHaveBeenCalledWith('The database session has ended.'), { timeout: 3000 });
    expect(beats).toBe(2);
  });

  it('switches database: the server replaces the session, and the current database is marked', async () => {
    let body = null;
    server.use(
      http.post('/api/db-sessions/external', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(session({ id: 'NewSessionIdAAAAAAAAAA', database: body.database }), { status: 201 });
      }),
    );
    const refresh = vi.fn();
    const onSwitched = vi.fn();
    const onSwitching = vi.fn();
    const { container } = render(
      <SessionBar session={session()} profile="external" discovery={discovery({ databases: dbs('sales', 'hr'), refresh })} onSwitched={onSwitched} onSwitching={onSwitching} />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Switch database' }));
    expect(refresh).toHaveBeenCalledOnce(); // the list is refreshed when the picker opens
    const picker = screen.getByRole('region', { name: 'Switch database' });
    expect(within(picker).getByText('current')).toBeInTheDocument();
    expect(within(picker).queryByRole('button', { name: /sales/ })).toBeNull(); // the current one isn't offered
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(within(picker).getByRole('button', { name: /hr/ }));
    await waitFor(() => expect(onSwitched).toHaveBeenCalledWith(expect.objectContaining({ id: 'NewSessionIdAAAAAAAAAA', database: 'hr' })));
    expect(body).toEqual({ database: 'hr', replaces: 'AbCdEfGhIjKlMnOpQrStUv' });
    expect(onSwitching.mock.calls).toEqual([['hr'], [null]]);
    expect(screen.queryByRole('region', { name: 'Switch database' })).toBeNull();
  });

  it('a failed switch says the current session is still open and does not switch', async () => {
    server.use(http.post('/api/db-sessions/external', () => envelope(422, 'CONNECT_FAILED', 'raw')));
    const onSwitched = vi.fn();
    render(<SessionBar session={session()} profile="external" discovery={discovery({ databases: dbs('sales', 'hr') })} onSwitched={onSwitched} />);
    await userEvent.click(screen.getByRole('button', { name: 'Switch database' }));
    await userEvent.click(screen.getByRole('button', { name: /hr/ }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not switch to hr');
    expect(alert).toHaveTextContent('You are still connected to sales');
    expect(onSwitched).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Database session')).toHaveTextContent('sales');
  });

  it('at the session limit, a switch explains what to do', async () => {
    server.use(http.post('/api/db-sessions/external', () => envelope(409, 'SESSION_LIMIT', 'You already have 3 open database sessions; close one first.')));
    render(<SessionBar session={session()} profile="external" discovery={discovery({ databases: dbs('sales', 'hr') })} onSwitched={() => {}} />);
    await userEvent.click(screen.getByRole('button', { name: 'Switch database' }));
    await userEvent.click(screen.getByRole('button', { name: /hr/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Disconnect this session \(or another one\) first/);
  });

  it('with an open transaction, asks first and sends the confirmation; cancelling sends nothing', async () => {
    const bodies = [];
    server.use(
      http.post('/api/db-sessions/external', async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json(session({ id: 'NewSessionIdAAAAAAAAAA', database: 'hr' }), { status: 201 });
      }),
    );
    const onSwitched = vi.fn();
    render(<SessionBar session={session()} profile="external" discovery={discovery({ databases: dbs('sales', 'hr') })} onSwitched={onSwitched} transactionOpen />);
    await userEvent.click(screen.getByRole('button', { name: 'Switch database' }));
    await userEvent.click(screen.getByRole('button', { name: /hr/ }));
    const dialog = screen.getByRole('dialog', { name: 'Switch with an open transaction?' });
    expect(dialog).toHaveTextContent(/rolls back everything in the open transaction/);
    expect(dialog).toHaveTextContent(/nothing is committed for you/);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(bodies).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: /hr/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Roll back and switch' }));
    await waitFor(() => expect(onSwitched).toHaveBeenCalledOnce());
    expect(bodies).toEqual([{ database: 'hr', replaces: 'AbCdEfGhIjKlMnOpQrStUv', discardTransaction: true }]);
  });

  it('when the server reports a transaction the page did not know about, it asks before trying again', async () => {
    const bodies = [];
    server.use(
      http.post('/api/db-sessions/external', async ({ request }) => {
        const b = await request.json();
        bodies.push(b);
        return b.discardTransaction
          ? HttpResponse.json(session({ id: 'NewSessionIdAAAAAAAAAA', database: 'hr' }), { status: 201 })
          : HttpResponse.json({ error: { code: 'TRANSACTION_OPEN', message: 'open', transaction: 'OPEN', requestId: 'r' } }, { status: 409 });
      }),
    );
    const onSwitched = vi.fn();
    render(<SessionBar session={session()} profile="external" discovery={discovery({ databases: dbs('sales', 'hr') })} onSwitched={onSwitched} />);
    await userEvent.click(screen.getByRole('button', { name: 'Switch database' }));
    await userEvent.click(screen.getByRole('button', { name: /hr/ }));
    await userEvent.click(await screen.findByRole('button', { name: 'Roll back and switch' }));
    await waitFor(() => expect(onSwitched).toHaveBeenCalledOnce());
    expect(bodies.map((b) => Boolean(b.discardTransaction))).toEqual([false, true]);
  });

  it('cannot switch while a statement runs, and reports a session that ended meanwhile', async () => {
    server.use(http.post('/api/db-sessions/external', () => envelope(410, 'SESSION_LOST', 'gone')));
    const onLost = vi.fn();
    const { rerender } = render(<SessionBar session={session()} profile="external" discovery={discovery({ databases: dbs('sales', 'hr') })} busy onLost={onLost} />);
    expect(screen.getByRole('button', { name: 'Switch database' })).toBeDisabled();
    rerender(<SessionBar session={session()} profile="external" discovery={discovery({ databases: dbs('sales', 'hr') })} onLost={onLost} />);
    await userEvent.click(screen.getByRole('button', { name: 'Switch database' }));
    await userEvent.click(screen.getByRole('button', { name: /hr/ }));
    await waitFor(() => expect(onLost).toHaveBeenCalledWith('The database session had already ended.'));
  });

  it('the picker shows discovery problems and closes', async () => {
    const refresh = vi.fn();
    render(<SessionBar session={session()} profile="external" discovery={discovery({ status: 'failed', error: { code: 'CONNECT_FAILED' }, refresh })} />);
    await userEvent.click(screen.getByRole('button', { name: 'Switch database' }));
    expect(screen.getByText('Could not list your databases')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Refresh list' }));
    expect(refresh).toHaveBeenCalledTimes(2);
    await userEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('region', { name: 'Switch database' })).toBeNull();
  });
});

describe('LostSession', () => {
  it('reconnects to the same database, or goes back to the list', async () => {
    let calls = 0;
    server.use(
      http.post('/api/db-sessions/internal', async ({ request }) => {
        calls += 1;
        const b = await request.json();
        expect(b).toEqual({ database: 'ops' });
        return calls === 1 ? envelope(503, 'AUDIT_UNAVAILABLE', 'x') : HttpResponse.json(session({ profile: 'internal', database: 'ops', id: 'AgainAAAAAAAAAAAAAAAAA' }), { status: 201 });
      }),
    );
    const onReconnected = vi.fn();
    const onChooseAnother = vi.fn();
    render(<LostSession profile="internal" database="ops" message="It ended." onReconnected={onReconnected} onChooseAnother={onChooseAnother} />);
    expect(screen.getByRole('alert')).toHaveTextContent('It ended.');
    await userEvent.click(screen.getByRole('button', { name: 'Reconnect to ops' }));
    expect(await screen.findByText(/audit service is unavailable/)).toBeInTheDocument();
    expect(onReconnected).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Reconnect to ops' }));
    await waitFor(() => expect(onReconnected).toHaveBeenCalledWith(expect.objectContaining({ id: 'AgainAAAAAAAAAAAAAAAAA' })));
    await userEvent.click(screen.getByRole('button', { name: 'Choose another database' }));
    expect(onChooseAnother).toHaveBeenCalledOnce();
  });
});

describe('Workspace with connections', () => {
  const opsHandlers = (over = {}) => [
    http.post('/api/profiles/internal/databases', over.discover || (() => HttpResponse.json({ databases: dbs('ops', 'reports') }))),
    http.post('/api/db-sessions/internal', async ({ request }) => {
      const b = await request.json();
      return HttpResponse.json(session({ profile: 'internal', database: b.database, id: b.replaces ? 'SwitchedAAAAAAAAAAAAAA' : 'FirstAAAAAAAAAAAAAAAAA' }), { status: 201 });
    }),
    http.delete('/api/db-sessions/:id', () => new HttpResponse(null, { status: 204 })),
  ];

  it('discovers the server when it opens, without claiming a session, then connects and disconnects', async () => {
    server.use(...opsHandlers());
    render(<Workspace profile="internal" user={USER} onBack={() => {}} />);
    const status = screen.getByRole('region', { name: 'Connection status' });
    expect(status).toHaveTextContent('Checking…');
    await waitFor(() => expect(status).toHaveTextContent('Reachable'));
    expect(status).toHaveTextContent('No session');
    expect(status).toHaveTextContent('none open');
    expect(status).toHaveTextContent('CORP\\alice');
    expect(screen.queryByLabelText('Database session')).toBeNull();
    await userEvent.click(await screen.findByRole('button', { name: /ops/ }));
    expect(await screen.findByLabelText('Database session')).toHaveTextContent('ops');
    expect(status).toHaveTextContent('Open');
    expect(status).toHaveTextContent('ops');
    expect(await screen.findByRole('region', { name: 'Objects' })).toBeInTheDocument();
    expect(await screen.findByRole('textbox', { name: 'SQL editor' })).toBeInTheDocument();
    expect(screen.getByText('Running SQL is switched off by the administrator')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Run' })).toBeDisabled();
    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: 'Disconnect' }));
    });
    expect(await screen.findByText('No database open')).toBeInTheDocument();
    expect(status).toHaveTextContent('No session');
    expect(getLastDatabase('internal', USER.sid)).toBe('ops');
  });

  it('a discovery failure is shown as the server state, never as a session state', async () => {
    server.use(...opsHandlers({ discover: () => envelope(422, 'CONNECT_FAILED', 'x') }));
    render(<Workspace profile="internal" user={USER} onBack={() => {}} />);
    const status = screen.getByRole('region', { name: 'Connection status' });
    await waitFor(() => expect(status).toHaveTextContent('Could not connect'));
    expect(status).toHaveTextContent('No session');
    expect(screen.getByText('Could not list your databases')).toBeInTheDocument();
  });

  it('switches database keeping the editor text, and offers the last database next time', async () => {
    const schemaCalls = [];
    server.use(
      ...opsHandlers(),
      http.get('/api/metadata/:id/schemas', ({ params }) => {
        schemaCalls.push(params.id);
        return HttpResponse.json({ schemas: [], loadedAt: '2026-10-19T08:00:00Z' });
      }),
    );
    const { unmount } = render(<Workspace profile="internal" user={USER} executionEnabled onBack={() => {}} />);
    await userEvent.click(await screen.findByRole('button', { name: /ops/ }));
    const editor = await screen.findByRole('textbox', { name: 'SQL editor' });
    await userEvent.click(editor);
    await userEvent.keyboard('SELECT 1');
    await userEvent.click(screen.getByRole('button', { name: 'Switch database' }));
    await userEvent.click(within(screen.getByRole('region', { name: 'Switch database' })).getByRole('button', { name: /reports/ }));
    await waitFor(() => expect(screen.getByLabelText('Database session')).toHaveTextContent('reports'));
    expect(screen.getByRole('region', { name: 'Connection status' })).toHaveTextContent('reports');
    expect(await screen.findByRole('textbox', { name: 'SQL editor' })).toHaveTextContent('SELECT 1');
    await waitFor(() => expect(schemaCalls).toEqual(['FirstAAAAAAAAAAAAAAAAA', 'SwitchedAAAAAAAAAAAAAA'])); // the explorer reloaded
    unmount();
    render(<Workspace profile="internal" user={USER} onBack={() => {}} />);
    expect(await screen.findByRole('button', { name: 'Connect to reports' })).toBeInTheDocument();
  });

  it('a session ended by the server can be reconnected in one step', async () => {
    server.use(...opsHandlers(), http.post('/api/db-sessions/:id/heartbeat', () => envelope(410, 'SESSION_LOST', 'The connection to the database was lost.')));
    vi.useFakeTimers({ shouldAdvanceTime: true });
    render(<Workspace profile="internal" user={USER} onBack={() => {}} />);
    await userEvent.click(await screen.findByRole('button', { name: /ops/ }));
    await screen.findByLabelText('Database session');
    await act(async () => {
      vi.advanceTimersByTime(31_000);
    });
    expect(await screen.findByText('The connection to the database was lost.')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Connection status' })).toHaveTextContent('Ended');
    vi.useRealTimers();
    server.use(http.post('/api/db-sessions/:id/heartbeat', () => HttpResponse.json({ state: 'OPEN', transaction: 'IDLE' })));
    await userEvent.click(screen.getByRole('button', { name: 'Reconnect to ops' }));
    expect(await screen.findByLabelText('Database session')).toHaveTextContent('ops');
    expect(screen.getByRole('region', { name: 'Connection status' })).toHaveTextContent('Open');
  });
});
