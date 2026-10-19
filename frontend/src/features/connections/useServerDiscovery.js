import { useCallback, useEffect, useRef, useState } from 'react';
import { listDatabases } from '../../api/dbsessions.js';

/**
 * Server discovery for one profile (FN-02/FN-03, admin Phase 1): asks the server which databases this user may
 * connect to. The server answers that by connecting to the PostgreSQL server as the user (Windows sign-in, a short
 * connection to the maintenance database), so a successful answer is the authoritative evidence that the server is
 * reachable for this user. It says nothing about a database session: none is opened here.
 *
 * status: 'checking' (first answer pending), 'reachable', or 'failed' (`error` says why: the server could not be
 * reached, sign-in or access was refused, too many attempts, ...). A refresh keeps the previous list on screen.
 * Runs once on mount, and again on `refresh()`.
 */
export default function useServerDiscovery(profile) {
  const [state, setState] = useState({ status: 'checking', databases: [], checkedAt: null, error: null, refreshing: true });
  const latest = useRef(0);

  const refresh = useCallback(async () => {
    const call = ++latest.current;
    setState((s) => ({ ...s, refreshing: true }));
    try {
      const databases = await listDatabases(profile);
      if (call === latest.current) setState({ status: 'reachable', databases, checkedAt: new Date(), error: null, refreshing: false });
      return databases;
    } catch (error) {
      if (call === latest.current) setState((s) => ({ ...s, status: 'failed', checkedAt: new Date(), error, refreshing: false }));
      return null;
    }
  }, [profile]);

  useEffect(() => {
    refresh();
    return () => {
      latest.current += 1; // ignore answers that arrive after unmount
    };
  }, [refresh]);

  return { ...state, refresh };
}
