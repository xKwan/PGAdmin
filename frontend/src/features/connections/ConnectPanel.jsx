import { useCallback, useEffect, useState } from 'react';
import { closeSession, describeConnectionError, listSessions, openSession } from '../../api/dbsessions.js';
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
        Back to my databases
      </button>
    </section>
  );
}

/** The databases from discovery, each a button that connects; `current` (when switching) is marked, not a button. */
export function DatabaseList({ databases, current, lastDatabase, onChoose, label = 'Databases' }) {
  return (
    <ul className="db-list" aria-label={label}>
      {databases.map((d) => (
        <li key={d.name}>
          {d.name === current ? (
            <span className="db-item db-current" aria-current="true">
              <span className="db-name">{d.name}</span>
              <StatusBadge tone="info">current</StatusBadge>
            </span>
          ) : (
            <button type="button" className="db-item" onClick={() => onChoose(d.name)}>
              <span className="db-name">{d.name}</span>
              <span>
                {d.name === lastDatabase ? <StatusBadge tone="info">last used</StatusBadge> : null}
                {d.owned ? <StatusBadge tone="ok">owner</StatusBadge> : <StatusBadge tone="neutral">shared</StatusBadge>}
              </span>
            </button>
          )}
        </li>
      ))}
    </ul>
  );
}

/**
 * Choose a database and connect (FN-02). There is no username or password: the server connects as you with your
 * Windows sign-in, and only lists databases you may connect to (ADR-0003). The list comes from the workspace's
 * server discovery (`discovery`, see useServerDiscovery), which runs as soon as the workspace opens. A session is
 * opened only when the user chooses a database; `onConnected` receives it once the server has confirmed it.
 */
export default function ConnectPanel({ profile, discovery, lastDatabase, onConnected, onConnecting }) {
  const [state, setState] = useState({ phase: 'idle' });

  const connect = async (database) => {
    setState({ phase: 'connecting', target: database });
    onConnecting?.(database);
    try {
      const session = await openSession(profile, database);
      setState({ phase: 'idle' });
      onConnected(session);
    } catch (error) {
      setState({ phase: 'error', error, target: database });
      onConnecting?.(null);
    }
  };
  const backToList = () => {
    setState({ phase: 'idle' });
    discovery.refresh();
  };

  if (state.phase === 'connecting') return <Spinner label={`Connecting to ${state.target}`} />;
  const listed = discovery.status === 'reachable' || (discovery.status === 'failed' && discovery.databases.length > 0);
  const offerLast = lastDatabase && discovery.databases.some((d) => d.name === lastDatabase);
  return (
    <div className="connect-panel">
      <div className="connect-intro">
        <p className="empty-title">No database open</p>
        <p className="muted">Choose a database to open a session. You connect as your own PostgreSQL role, with your Windows sign-in.</p>
      </div>
      {state.phase === 'error' ? (
        <ErrorPanel
          title={`Could not open ${state.target}`}
          error={{ ...state.error, message: describeConnectionError(state.error) }}
          onRetry={() => connect(state.target)}
        />
      ) : null}
      {state.phase === 'error' && state.error?.code === 'SESSION_LIMIT' ? <OpenSessions onDone={backToList} /> : null}
      {discovery.status === 'checking' ? <Spinner label="Finding your databases" /> : null}
      {discovery.status === 'failed' ? (
        <ErrorPanel
          title="Could not list your databases"
          error={{ ...discovery.error, message: describeConnectionError(discovery.error) }}
          onRetry={discovery.refresh}
        />
      ) : null}
      {listed ? (
        <>
          {offerLast ? (
            <div className="connect-last">
              <button type="button" className="btn btn-primary" onClick={() => connect(lastDatabase)}>
                Connect to {lastDatabase}
              </button>
            </div>
          ) : null}
          {discovery.databases.length === 0 ? (
            <EmptyState title="No databases">You have no database on this server that you can connect to.</EmptyState>
          ) : (
            <DatabaseList databases={discovery.databases} lastDatabase={lastDatabase} onChoose={connect} />
          )}
          <div className="connect-refresh">
            <button type="button" className="btn btn-link" onClick={discovery.refresh} disabled={discovery.refreshing}>
              {discovery.refreshing ? 'Refreshing…' : 'Refresh list'}
            </button>
          </div>
        </>
      ) : null}
    </div>
  );
}
