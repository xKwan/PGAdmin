import { afterEach, describe, expect, it, vi } from 'vitest';
import { envelope, http, HttpResponse, server } from '../mocks/server.js';
import { api, ApiError, setCsrfToken, setUnauthenticatedHandler } from './client.js';

afterEach(() => {
  setCsrfToken(null);
  setUnauthenticatedHandler(() => {});
});

describe('api client', () => {
  it('returns JSON and null for 204', async () => {
    server.use(
      http.get('/api/thing', () => HttpResponse.json({ a: 1 })),
      http.post('/api/none', () => new HttpResponse(null, { status: 204 })),
    );
    expect(await api('/api/thing')).toEqual({ a: 1 });
    expect(await api('/api/none', { method: 'POST' })).toBeNull();
  });

  it('sends the CSRF token and JSON on unsafe requests only', async () => {
    const seen = [];
    server.use(
      http.post('/api/x', async ({ request }) => {
        seen.push({ csrf: request.headers.get('X-CSRF-Token'), ct: request.headers.get('Content-Type'), body: await request.text() });
        return HttpResponse.json({});
      }),
      http.get('/api/x', ({ request }) => {
        seen.push({ csrf: request.headers.get('X-CSRF-Token') });
        return HttpResponse.json({});
      }),
    );
    setCsrfToken('tok-123');
    await api('/api/x', { method: 'POST', body: { n: 1 } });
    await api('/api/x');
    expect(seen[0]).toEqual({ csrf: 'tok-123', ct: 'application/json', body: '{"n":1}' });
    expect(seen[1].csrf).toBeNull();
  });

  it('turns the error envelope into an ApiError with its kind', async () => {
    const cases = [
      [403, 'FORBIDDEN', 'forbidden'],
      [503, 'AUDIT_UNAVAILABLE', 'audit-unavailable'],
      [503, 'UNAVAILABLE', 'error'],
      [400, 'UNKNOWN_FIELD', 'error'],
    ];
    for (const [status, code, kind] of cases) {
      server.use(http.get('/api/e', () => envelope(status, code, 'Message for ' + code)));
      const err = await api('/api/e').catch((e) => e);
      expect(err).toBeInstanceOf(ApiError);
      expect([err.status, err.code, err.kind, err.message, err.requestId]).toEqual([status, code, kind, 'Message for ' + code, '0123456789abcdef']);
    }
  });

  it('calls the unauthenticated handler on 401', async () => {
    const handler = vi.fn();
    setUnauthenticatedHandler(handler);
    server.use(http.get('/api/me', () => envelope(401, 'SESSION_EXPIRED', 'expired')));
    const err = await api('/api/me').catch((e) => e);
    expect(err.kind).toBe('unauthenticated');
    expect(handler).toHaveBeenCalledWith(err);
  });

  it('treats a renewed session (403 SESSION_RENEWED) like an expired one, but not a CSRF failure', async () => {
    const handler = vi.fn();
    setUnauthenticatedHandler(handler);
    server.use(http.post('/api/db-sessions/external', () => envelope(403, 'SESSION_RENEWED', 'renewed')));
    const renewed = await api('/api/db-sessions/external', { method: 'POST', body: {} }).catch((e) => e);
    expect(renewed.kind).toBe('unauthenticated');
    expect(handler).toHaveBeenCalledTimes(1);
    server.use(http.post('/api/db-sessions/external', () => envelope(403, 'CSRF_FAILED', 'blocked')));
    const csrf = await api('/api/db-sessions/external', { method: 'POST', body: {} }).catch((e) => e);
    expect(csrf.kind).toBe('forbidden');
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('handles non-JSON errors and network failures', async () => {
    server.use(http.get('/api/plain', () => new HttpResponse('oops', { status: 502, statusText: 'Bad Gateway' })));
    const err = await api('/api/plain').catch((e) => e);
    expect([err.status, err.code]).toEqual([502, 'HTTP_502']);
    server.use(http.get('/api/down', () => HttpResponse.error()));
    const net = await api('/api/down').catch((e) => e);
    expect(net.kind).toBe('network');
  });

  it('refuses paths outside the API and passes aborts through', async () => {
    await expect(api('https://evil.example/x')).rejects.toThrow(/only calls/);
    server.use(http.get('/api/slow', async () => new Promise(() => {})));
    const ctrl = new AbortController();
    const p = api('/api/slow', { signal: ctrl.signal });
    ctrl.abort();
    await expect(p).rejects.toMatchObject({ name: 'AbortError' });
  });
});
