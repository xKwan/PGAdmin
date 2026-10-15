import { useQuery } from '@tanstack/react-query';
import { api, setCsrfToken } from './client.js';

/** GET /api/me, remembering the CSRF token for later state-changing requests. */
export async function fetchMe() {
  const me = await api('/api/me');
  setCsrfToken(me.csrfToken);
  return me;
}

export function useMe() {
  return useQuery({ queryKey: ['me'], queryFn: fetchMe, retry: false, staleTime: 60_000 });
}

export async function logout() {
  await api('/api/logout', { method: 'POST' });
  setCsrfToken(null);
}
