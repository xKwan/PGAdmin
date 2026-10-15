import { useState } from 'react';
import { describeConnectionError, listDatabases, openSession } from '../../api/dbsessions.js';
import { EmptyState, ErrorPanel, Spinner, StatusBadge } from '../../components/common/index.jsx';

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
