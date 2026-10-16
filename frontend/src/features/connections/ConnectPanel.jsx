import { useCallback, useEffect, useState } from 'react';
import { closeSession, describeConnectionError, listDatabases, listSessions, openSession } from '../../api/dbsessions.js';
import { EmptyState, ErrorPanel, Spinner, StatusBadge } from '../../components/common/index.jsx';

/**
 * The user's open DB sessions of this sign-in, offered when the per-user limit is reached (for example after a
 * page reload left sessions behind): each can be disconnected here (FN-03).
 */
function OpenSessions({ onDone }) {
  const [loaded, setLoaded] = useState({ version: -1, sessions: null, error: null });
  const [version, setVersion] = useState(0);
  const refresh = useCallback(() => setVersion((v) => v + 1), []);
  useEffect(() => {
    let live = true;
    listSessions().then(
      (sessions) => live && setLoaded({ version, sessions, error: null }),
      (error) => live && setLoaded({ version, sessions: null, error }),
    );
    return () => {
      live = false;
    };
  }, [version]);
  const disconnect = async (id) => {
    try {
      await closeSession(id);
    } catch {
      // already gone on the server
    }
    refresh();
  };
  // While a refresh runs, the previous list stays on screen; an error shows only for the latest request.
  const error = loaded.version === version ? loaded.error : null;
  const { sessions } = loaded;
  if (error) return <ErrorPanel title="Could not list your sessions" error={error} onRetry={refresh} />;
  if (!sessions) return <Spinner label="Finding your open sessions" />;
  return (
    <section className="open-sessions" aria-label="Your open sessions">
      <p>Disconnect a session you no longer need, then connect again.</p>
      <ul className="db-list">
        {sessions.map((s) => (
          <li key={s.id} className="db-item">
            <span className="db-name">
              {s.database} <span className="muted">since {new Date(s.createdAt).toLocaleTimeString('en-GB')}</span>
            </span>
            <button type="button" className="btn" onClick={() => disconnect(s.id)}>
              Disconnect {s.database}
            </button>
          </li>
        ))}
      </ul>
      <button type="button" className="btn btn-primary" onClick={onDone}>
        Show my databases
      </button>
    </section>
  );
}

/**
 * Choose a database and connect (FN-02). There is no username or password: the server connects as you with your
 * Windows sign-in, and only lists databases you may connect to (ADR-0003).
 */
export default function ConnectPanel({ profile, onConnected }) {
  const [state, setState] = useState({ phase: 'idle' });

  const load = async () => {
    setState({ phase: 'listing' });
    try {
      const databases = await listDatabases(profile);
      setState({ phase: 'listed', databases });
    } catch (error) {
      setState({ phase: 'error', error });
    }
  };

  const connect = async (database) => {
    setState((s) => ({ ...s, phase: 'connecting', target: database }));
    try {
      const session = await openSession(profile, database);
      setState({ phase: 'idle' });
      onConnected(session);
    } catch (error) {
      setState((s) => ({ ...s, phase: 'error', error }));
    }
  };

  if (state.phase === 'listing') return <Spinner label="Finding your databases" />;
  if (state.phase === 'connecting') return <Spinner label={`Connecting to ${state.target}`} />;
  return (
    <div className="connect-panel">
      {state.phase === 'error' ? (
        <ErrorPanel title="Connection problem" error={{ ...state.error, message: describeConnectionError(state.error) }} onRetry={load} />
      ) : null}
      {state.phase === 'error' && state.error?.code === 'SESSION_LIMIT' ? <OpenSessions onDone={load} /> : null}
      {state.phase === 'listed' ? (
        state.databases.length === 0 ? (
          <EmptyState title="No databases">You have no database on this server that you can connect to.</EmptyState>
        ) : (
          <ul className="db-list" aria-label="Databases">
            {state.databases.map((d) => (
              <li key={d.name}>
                <button type="button" className="db-item" onClick={() => connect(d.name)}>
                  <span className="db-name">{d.name}</span>
                  {d.owned ? <StatusBadge tone="ok">owner</StatusBadge> : <StatusBadge tone="neutral">shared</StatusBadge>}
                </button>
              </li>
            ))}
          </ul>
        )
      ) : null}
      {state.phase === 'idle' ? (
        <EmptyState
          title="Not connected"
          action={
            <button type="button" className="btn btn-primary" onClick={load}>
              Show my databases
            </button>
          }
        >
          You connect as your own PostgreSQL role, with your Windows sign-in.
        </EmptyState>
      ) : null}
    </div>
  );
}
