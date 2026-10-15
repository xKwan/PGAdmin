import { QueryClient } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { envelope, http, HttpResponse, meBody, server } from '../mocks/server.js';
import { axeViolations } from '../test/a11y.js';
import App, { makeUnauthenticatedHandler } from './App.jsx';

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<App client={client} />);
}

describe('App', () => {
  beforeEach(() => window.history.replaceState(null, '', '/'));
  afterEach(() => vi.restoreAllMocks());

  it('shows both servers to a DataAdmin and opens a workspace', async () => {
    server.use(http.get('/api/me', () => HttpResponse.json(meBody({ dataAdmin: true, account: 'CORP\\dba' }))));
    const { container } = renderApp();
    expect(await screen.findByRole('heading', { name: 'Choose a database server' })).toBeInTheDocument();
    expect(screen.getByText('DataAdmin')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Internal PostgreSQL/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /External PostgreSQL/ })).toBeInTheDocument();
    expect(screen.getByText(/Running SQL is currently switched off/)).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);

    await userEvent.click(screen.getByRole('button', { name: /Internal PostgreSQL/ }));
    const ws = screen.getByRole('region', { name: 'Internal PostgreSQL workspace' });
    expect(within(ws).getByRole('complementary', { name: 'Object explorer' })).toBeInTheDocument();
    expect(within(ws).getByRole('region', { name: 'SQL editor' })).toBeInTheDocument();
    expect(within(ws).getByRole('region', { name: 'Results' })).toBeInTheDocument();
    expect(within(ws).getAllByRole('separator')).toHaveLength(2);
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: '← Servers' }));
    expect(screen.getByRole('heading', { name: 'Choose a database server' })).toBeInTheDocument();
  });

  it('hides the internal server from a non-DataAdmin', async () => {
    server.use(http.get('/api/me', () => HttpResponse.json(meBody({ dataAdmin: false, executionEnabled: true }))));
    renderApp();
    await screen.findByRole('heading', { name: 'Choose a database server' });
    expect(screen.queryByRole('button', { name: /Internal PostgreSQL/ })).toBeNull();
    expect(screen.getByRole('button', { name: /External PostgreSQL/ })).toBeInTheDocument();
    expect(screen.queryByText('DataAdmin')).toBeNull();
    expect(screen.queryByText(/switched off/)).toBeNull();
  });

  it('explains when no server is available', async () => {
    server.use(http.get('/api/me', () => HttpResponse.json(meBody({ internal: false, external: false }))));
    renderApp();
    expect(await screen.findByText(/No database servers are configured/)).toBeInTheDocument();
  });

  it('shows the insufficient-privileges page on 403', async () => {
    server.use(http.get('/api/me', () => envelope(403, 'DOMAIN_NOT_ALLOWED', 'Your account\'s domain is not allowed')));
    renderApp();
    expect(await screen.findByRole('heading', { name: 'Insufficient privileges' })).toBeInTheDocument();
    expect(screen.getByText(/approved domains/)).toBeInTheDocument();
  });

  it('shows the audit-unavailable page on 503 and retries', async () => {
    let calls = 0;
    server.use(
      http.get('/api/me', () => {
        calls += 1;
        return calls === 1 ? envelope(503, 'AUDIT_UNAVAILABLE', 'The audit database is unavailable') : HttpResponse.json(meBody());
      }),
    );
    renderApp();
    expect(await screen.findByRole('heading', { name: 'Auditing is unavailable' })).toBeInTheDocument();
    expect(screen.getByText(/Nothing was run/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('heading', { name: 'Choose a database server' })).toBeInTheDocument();
  });

  it('shows a generic error with a retry for other failures', async () => {
    server.use(http.get('/api/me', () => envelope(500, 'INTERNAL', 'Internal error')));
    renderApp();
    expect(await screen.findByText('Could not load your profile')).toBeInTheDocument();
    expect(screen.getByText('Code: INTERNAL')).toBeInTheDocument();
  });

  it('signs out', async () => {
    let loggedOut = false;
    server.use(
      http.get('/api/me', () => HttpResponse.json(meBody())),
      http.post('/api/logout', ({ request }) => {
        loggedOut = request.headers.get('X-CSRF-Token') === meBody().csrfToken;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderApp();
    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
    await waitFor(() => expect(loggedOut).toBe(true));
    expect(await screen.findByText(/signed out of the application/)).toBeInTheDocument();
  });

  it('reports a failed sign-out', async () => {
    server.use(
      http.get('/api/me', () => HttpResponse.json(meBody())),
      http.post('/api/logout', () => envelope(503, 'UNAVAILABLE', 'Service unavailable')),
    );
    renderApp();
    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
    expect(await screen.findByText('Service unavailable')).toBeInTheDocument();
  });
});

describe('401 handling', () => {
  it('reloads once to sign in again, then gives up instead of looping', () => {
    const replace = vi.fn();
    const giveUp = vi.fn();
    const first = makeUnauthenticatedHandler({ pathname: '/app', search: '', replace }, giveUp);
    first({ code: 'SESSION_EXPIRED' });
    expect(replace).toHaveBeenCalledWith('/app?reauth=1');
    expect(giveUp).not.toHaveBeenCalled();
    const second = makeUnauthenticatedHandler({ pathname: '/app', search: '?reauth=1', replace }, giveUp);
    second({ code: 'KERBEROS_REQUIRED' });
    expect(giveUp).toHaveBeenCalledWith({ code: 'KERBEROS_REQUIRED' });
    expect(replace).toHaveBeenCalledTimes(1);
  });

  it('shows the sign-in-failed page after a repeated 401', async () => {
    window.history.replaceState(null, '', '/?reauth=1');
    server.use(http.get('/api/me', () => envelope(401, 'KERBEROS_REQUIRED', 'Kerberos sign-in is required')));
    renderApp();
    expect(await screen.findByRole('heading', { name: 'Sign-in did not work' })).toBeInTheDocument();
    expect(screen.getByText(/needs a Kerberos sign-in/)).toBeInTheDocument();
    expect(screen.getByText('Request ID: 0123456789abcdef')).toBeInTheDocument();
  });

  it('removes the reauth marker after a successful sign-in', async () => {
    window.history.replaceState(null, '', '/?reauth=1');
    renderApp();
    await screen.findByRole('heading', { name: 'Choose a database server' });
    await waitFor(() => expect(window.location.search).toBe(''));
  });
});
