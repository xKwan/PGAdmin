import { QueryClient, QueryClientProvider, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useState } from 'react';
import { setUnauthenticatedHandler } from '../api/client.js';
import { logout, useMe } from '../api/session.js';
import { ErrorPanel, Spinner, ToastList } from '../components/common/index.jsx';
import AuditPage from '../features/audit/AuditPage.jsx';
import LandingPage from '../features/landing/LandingPage.jsx';
import { Forbidden, SignInFailed, Unavailable } from './StatusPages.jsx';
import Workspace from './Workspace.jsx';

const REAUTH = 'reauth';

/**
 * 401 handling: the app session is gone, so reload once to let the browser sign in again (Negotiate). If the page
 * was already reloaded for that reason, stop and show "sign-in failed" instead of looping.
 */
export function makeUnauthenticatedHandler(location, onGiveUp) {
  return (error) => {
    const params = new URLSearchParams(location.search);
    if (params.has(REAUTH)) {
      onGiveUp(error);
      return;
    }
    params.set(REAUTH, '1');
    location.replace(`${location.pathname}?${params.toString()}`);
  };
}

function Shell() {
  const queryClient = useQueryClient();
  const [signInError, setSignInError] = useState(null);
  const [profile, setProfile] = useState(null);
  const [toasts, setToasts] = useState([]);

  useEffect(() => {
    setUnauthenticatedHandler(makeUnauthenticatedHandler(window.location, setSignInError));
  }, []);

  const me = useMe();

  useEffect(() => {
    if (me.isSuccess && new URLSearchParams(window.location.search).has(REAUTH)) {
      window.history.replaceState(null, '', window.location.pathname);
    }
  }, [me.isSuccess]);

  const dismiss = useCallback((id) => setToasts((t) => t.filter((x) => x.id !== id)), []);
  const onLogout = async () => {
    try {
      await logout();
      queryClient.clear();
      setProfile(null);
      setToasts((t) => [...t, { id: Date.now(), tone: 'info', text: 'You have been signed out of the application.' }]);
    } catch (e) {
      setToasts((t) => [...t, { id: Date.now(), tone: 'danger', text: e.message }]);
    }
  };

  if (signInError) return <SignInFailed error={signInError} />;
  if (me.isPending) {
    return (
      <main className="center">
        <Spinner label="Signing in" />
      </main>
    );
  }
  if (me.isError) {
    const kind = me.error?.kind;
    if (kind === 'unauthenticated') return <Spinner label="Signing in again" />;
    if (kind === 'forbidden') return <Forbidden error={me.error} />;
    if (kind === 'audit-unavailable' || kind === 'network' || me.error?.status === 503) {
      return <Unavailable error={me.error} onRetry={() => me.refetch()} />;
    }
    return <ErrorPanel title="Could not load your profile" error={me.error} onRetry={() => me.refetch()} />;
  }

  return (
    <div className="app">
      <header className="topbar">
        <span className="brand">pgweb</span>
        <span className="topbar-user" title={me.data.user.sid}>
          {me.data.user.account}
        </span>
        <button type="button" className="btn btn-link" onClick={onLogout}>
          Sign out
        </button>
      </header>
      <main className="content">
        {profile === 'audit' ? <AuditPage onBack={() => setProfile(null)} /> : null}
        {profile && profile !== 'audit' ? <Workspace profile={profile} user={me.data.user} executionEnabled={me.data.capabilities.executionEnabled} onBack={() => setProfile(null)} /> : null}
        {profile ? null : <LandingPage me={me.data} onOpen={setProfile} />}
      </main>
      <ToastList toasts={toasts} onDismiss={dismiss} />
    </div>
  );
}

export default function App({ client }) {
  const [queryClient] = useState(() => client ?? new QueryClient({ defaultOptions: { queries: { refetchOnWindowFocus: false } } }));
  return (
    <QueryClientProvider client={queryClient}>
      <Shell />
    </QueryClientProvider>
  );
}
