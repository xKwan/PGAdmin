import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';

/** A /api/me body. */
export function meBody({ dataAdmin = false, internal = dataAdmin, external = true, executionEnabled = false, account = 'CORP\\alice' } = {}) {
  return {
    user: { account, sid: 'S-1-5-21-1-2-3-1001', dataAdmin, authMechanism: 'Kerberos' },
    capabilities: { internal, external, executionEnabled },
    session: { loginAt: '2026-10-16T08:00:00Z', expiresAt: '2026-10-16T16:00:00Z', idleTimeoutSeconds: 1800 },
    csrfToken: 'csrf-token-' + account.length,
  };
}

export function envelope(status, code, message, requestId = '0123456789abcdef') {
  return HttpResponse.json({ error: { code, message, requestId } }, { status });
}

export const handlers = [
  http.get('/api/me', () => HttpResponse.json(meBody())),
  http.get('/api/profiles', () =>
    HttpResponse.json({
      profiles: [
        { name: 'external', displayName: 'External PostgreSQL', requiresDataAdmin: false, confirmDestructive: true },
        { name: 'internal', displayName: 'Internal PostgreSQL', requiresDataAdmin: true, confirmDestructive: true },
      ],
    }),
  ),
  http.get('/api/metadata/:id/schemas', () => HttpResponse.json({ schemas: [], loadedAt: '2026-10-16T08:00:00Z' })),
];

export const server = setupServer(...handlers);
export { http, HttpResponse };
