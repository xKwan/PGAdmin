import { StatusBadge } from '../../components/common/index.jsx';

/**
 * The page after sign-in (FN-01). The internal card appears only when the server says the user may use it
 * (capabilities.internal = DataAdmin). Hiding it is cosmetic: the API enforces access (Phase 4 matrix).
 */
export default function LandingPage({ me, onOpen }) {
  const caps = me.capabilities;
  return (
    <section className="landing" aria-labelledby="landing-title">
      <h1 id="landing-title">Choose a database server</h1>
      <p className="landing-user">
        Signed in as <strong>{me.user.account}</strong>
        {me.user.dataAdmin ? <StatusBadge tone="info">DataAdmin</StatusBadge> : null}
      </p>
      <div className="cards">
        {caps.internal ? (
          <button type="button" className="card" onClick={() => onOpen('internal')}>
            <span className="card-title">Internal PostgreSQL</span>
            <span className="card-body">Restricted to DataAdmin. Connects as your own PostgreSQL role with your Windows sign-in.</span>
          </button>
        ) : null}
        {caps.external ? (
          <button type="button" className="card" onClick={() => onOpen('external')}>
            <span className="card-title">External PostgreSQL</span>
            <span className="card-body">Databases you own or that are shared with you, using your Windows sign-in.</span>
          </button>
        ) : null}
      </div>
      {!caps.internal && !caps.external ? <p>No database servers are configured for you. Contact the administrator.</p> : null}
      {!caps.executionEnabled ? (
        <p className="landing-note">
          <StatusBadge tone="warn">Read only</StatusBadge> Running SQL is currently switched off by the administrator.
        </p>
      ) : null}
    </section>
  );
}
