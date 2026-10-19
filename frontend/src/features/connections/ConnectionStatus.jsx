import { StatusBadge } from '../../components/common/index.jsx';

/**
 * What the server says about the profile's PostgreSQL server for this user: reachable when discovery succeeded or a
 * session is open (both confirmed by the backend), otherwise why the last check failed.
 */
export function serverState(discovery, sessionOpen) {
  if (sessionOpen) return { tone: 'ok', text: 'Reachable' };
  if (discovery.status === 'checking') return { tone: 'neutral', text: 'Checking…' };
  if (discovery.status === 'reachable') return { tone: 'ok', text: 'Reachable' };
  switch (discovery.error?.code) {
    case 'DELEGATION_HANDSHAKE':
      return { tone: 'warn', text: 'Windows sign-in incomplete' };
    case 'FORBIDDEN':
      return { tone: 'danger', text: 'Access refused' };
    case 'RATE_LIMITED':
      return { tone: 'warn', text: 'Too many failed attempts' };
    case 'AUDIT_UNAVAILABLE':
      return { tone: 'danger', text: 'Audit service unavailable' };
    case 'NETWORK':
      return { tone: 'danger', text: 'Application server unreachable' };
    case 'CONNECT_FAILED':
      return { tone: 'danger', text: 'Could not connect' };
    default:
      return { tone: 'danger', text: 'Check failed' };
  }
}

const SESSION = {
  none: { tone: 'neutral', text: () => 'No session' },
  opening: { tone: 'neutral', text: (t) => `Opening ${t}…` },
  open: { tone: 'ok', text: () => 'Open' },
  switching: { tone: 'warn', text: (t) => `Switching to ${t}…` },
  lost: { tone: 'danger', text: () => 'Ended' },
};

/**
 * The workspace's connection status (FN-03): who you are signed in as, whether the PostgreSQL server is reachable,
 * which database is open, and the session state, each shown separately so they can't contradict each other.
 * `phase`: none | opening | open | switching | lost; `target` is the database being opened or switched to.
 */
export default function ConnectionStatus({ user, serverName, discovery, phase, database, target }) {
  const sessionOpen = phase === 'open' || phase === 'switching';
  const server = serverState(discovery, sessionOpen);
  const session = SESSION[phase] || SESSION.none;
  const checked = discovery.checkedAt ? `Last checked ${discovery.checkedAt.toLocaleTimeString('en-GB')}` : undefined;
  return (
    <section className="connection-status" aria-label="Connection status">
      {user ? (
        <span className="cs-item">
          <span className="cs-label">Signed in</span>
          <span className="cs-value" title={user.sid}>
            {user.account}
            {user.authMechanism ? <span className="muted"> ({user.authMechanism})</span> : null}
          </span>
        </span>
      ) : null}
      <span className="cs-item">
        <span className="cs-label">Server</span>
        <span className="cs-value">{serverName}</span>
        <StatusBadge tone={server.tone} title={checked}>
          {server.text}
        </StatusBadge>
      </span>
      <span className="cs-item">
        <span className="cs-label">Database</span>
        <span className="cs-value cs-database">{database || <span className="muted">none open</span>}</span>
      </span>
      <span className="cs-item">
        <span className="cs-label">Session</span>
        <StatusBadge tone={session.tone}>{session.text(target)}</StatusBadge>
      </span>
    </section>
  );
}
