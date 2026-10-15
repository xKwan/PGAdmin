// Full-page states (plan Phase 5): sign-in failed, insufficient privileges, audit or server unavailable.

export function SignInFailed({ error }) {
  const kerberos = error?.code === 'KERBEROS_REQUIRED';
  return (
    <section className="status-page" role="alert" aria-labelledby="signin-title">
      <h1 id="signin-title">Sign-in did not work</h1>
      {kerberos ? (
        <p>
          This application needs a Kerberos sign-in. Open it from a domain-joined computer, using its full host name (the
          address your administrator gave you), not an IP address.
        </p>
      ) : error?.code === 'SESSION_EXPIRED' ? (
        <p>Your session has ended. Reload the page to sign in again.</p>
      ) : (
        <p>Your browser did not sign you in automatically. Make sure you are signed in to Windows with your domain account.</p>
      )}
      {error?.requestId ? <p className="error-meta">Request ID: {error.requestId}</p> : null}
      <button type="button" className="btn btn-primary" onClick={() => window.location.assign(window.location.pathname)}>
        Try again
      </button>
    </section>
  );
}

export function Forbidden({ error }) {
  return (
    <section className="status-page" role="alert" aria-labelledby="forbidden-title">
      <h1 id="forbidden-title">Insufficient privileges</h1>
      <p>{error?.message || 'You are not allowed to use this function.'}</p>
      {error?.code === 'DOMAIN_NOT_ALLOWED' ? <p>Only accounts from the organisation's approved domains can use this application.</p> : null}
    </section>
  );
}

export function Unavailable({ error, onRetry }) {
  const audit = error?.code === 'AUDIT_UNAVAILABLE';
  return (
    <section className="status-page" role="alert" aria-labelledby="unavailable-title">
      <h1 id="unavailable-title">{audit ? 'Auditing is unavailable' : 'The service is unavailable'}</h1>
      <p>
        {audit
          ? 'Every action is recorded for audit, and the audit database cannot be reached right now, so actions are paused. Nothing was run.'
          : error?.message || 'The server could not be reached.'}
      </p>
      <button type="button" className="btn" onClick={onRetry}>
        Try again
      </button>
    </section>
  );
}
