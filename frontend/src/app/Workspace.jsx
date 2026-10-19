import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { describeReasons } from '../api/execution.js';
import { listProfiles } from '../api/dbsessions.js';
import { ConfirmDialog, EmptyState, Spinner, StatusBadge } from '../components/common/index.jsx';
import { Splitter } from '../components/common/Splitter.jsx';
import ConnectPanel from '../features/connections/ConnectPanel.jsx';
import ConnectionStatus from '../features/connections/ConnectionStatus.jsx';
import { getLastDatabase, setLastDatabase } from '../features/connections/lastDatabase.js';
import useServerDiscovery from '../features/connections/useServerDiscovery.js';
import ObjectExplorer from '../features/explorer/ObjectExplorer.jsx';
import SessionBar, { LostSession } from '../features/connections/SessionBar.jsx';
import ResultView from '../features/results/ResultView.jsx';
import useExecution from '../features/results/useExecution.js';
import TransactionBar, { LifetimeWarning } from '../features/transactions/TransactionBar.jsx';

// The editor (CodeMirror) is its own chunk, loaded when a session opens (bundle budget, plan Phase 8).
const SqlEditor = lazy(() => import('../features/editor/SqlEditor.jsx'));

const NAMES = { internal: 'Internal PostgreSQL', external: 'External PostgreSQL' };

function EditorPane({ session, executionEnabled, execution, controllerRef, initialDoc }) {
  if (!session) {
    return <EmptyState title="SQL editor">Available once you are connected.</EmptyState>;
  }
  const failed = execution.tx.state === 'FAILED';
  const canRun = executionEnabled && !execution.running && !failed;
  return (
    <>
      <div className="editor-toolbar">
        <button type="button" className="btn btn-primary" onClick={() => controllerRef.current?.run()} disabled={!canRun}>
          Run
        </button>
        <span className="muted">Ctrl+Enter runs the selection, or the statement at the cursor. One statement at a time.</span>
        {executionEnabled ? null : <StatusBadge tone="warn">Running SQL is switched off by the administrator</StatusBadge>}
        {failed ? <StatusBadge tone="danger">The transaction failed: roll back to continue</StatusBadge> : null}
      </div>
      {executionEnabled ? <TransactionBar execution={execution} /> : null}
      <LifetimeWarning lifetime={execution.tx.lifetime} transaction={execution.tx.state} />
      <Suspense fallback={<Spinner label="Loading the editor" />}>
        <SqlEditor key={session.id} initialDoc={initialDoc} onRun={execution.run} canRun={canRun} controllerRef={controllerRef} />
      </Suspense>
    </>
  );
}

/** The profile's banner (for example PRODUCTION), from the server configuration. */
function useProfileBanner(profile) {
  const [banner, setBanner] = useState(null);
  useEffect(() => {
    let live = true;
    listProfiles().then(
      (profiles) => {
        const p = profiles.find((x) => x.name === profile);
        if (live) setBanner(p?.bannerText ? { text: p.bannerText, tone: p.bannerTone || 'warn' } : null);
      },
      () => {}, // the banner is a convenience; the workspace works without it
    );
    return () => {
      live = false;
    };
  }, [profile]);
  return banner;
}

const CLOSED_BY_SERVER = 'The database session was closed by the server. Anything not committed was rolled back.';

/**
 * The DBeaver-style workspace: object explorer on the left, editor above results on the right.
 *
 * Connection state (admin Phase 1): server discovery runs as soon as the workspace opens and is the evidence that the
 * PostgreSQL server is reachable for this user; a database session exists only once the server has confirmed it
 * (open, switch or reconnect). No session is opened automatically: each one is audited and counts against the
 * per-user limit, so the user chooses (the last database used is offered first). `user` is /api/me's user.
 */
export default function Workspace({ profile, onBack, executionEnabled = false, user = null }) {
  const [session, setSession] = useState(null);
  const [lost, setLost] = useState(null);
  const [opening, setOpening] = useState(null);
  const [switchingTo, setSwitchingTo] = useState(null);
  const [carryDoc, setCarryDoc] = useState('');
  const [lastDatabase, setLast] = useState(() => getLastDatabase(profile, user?.sid));
  const controllerRef = useRef(null);
  const discovery = useServerDiscovery(profile);
  const execution = useExecution(session?.id, { onSessionEnded: () => setLost(CLOSED_BY_SERVER) });
  const banner = useProfileBanner(profile);
  const name = NAMES[profile] || profile;

  const connected = (s) => {
    setLost(null);
    setOpening(null);
    setSession(s);
    setLastDatabase(profile, user?.sid, s.database);
    setLast(s.database);
  };
  /** Switch or reconnect: the editor keeps its text; results, explorer and transaction state start afresh. */
  const replaced = (s) => {
    setCarryDoc(controllerRef.current?.text?.() ?? '');
    connected(s);
  };
  const disconnected = () => {
    setCarryDoc(controllerRef.current?.text?.() ?? '');
    setLost(null);
    setSession(null);
    discovery.refresh();
  };
  const sessionLost = (message) => {
    setLost(message);
    discovery.refresh();
  };
  const showPosition = (position) => controllerRef.current?.showPosition(execution.lastFrom, position);

  let phase = opening ? 'opening' : 'none';
  if (session) phase = lost ? 'lost' : switchingTo ? 'switching' : 'open';

  let bar = null;
  if (session && lost) {
    bar = <LostSession profile={profile} database={session.database} message={lost} onReconnected={replaced} onChooseAnother={disconnected} />;
  } else if (session) {
    bar = (
      <SessionBar
        session={session}
        profile={profile}
        discovery={discovery}
        onClosed={disconnected}
        onLost={sessionLost}
        onSwitched={replaced}
        onSwitching={setSwitchingTo}
        onHeartbeat={execution.observeHeartbeat}
        transactionOpen={execution.tx.state !== 'IDLE'}
        busy={execution.running}
      />
    );
  }

  return (
    <section className="workspace" aria-label={`${name} workspace`}>
      {banner ? (
        <div className={`profile-banner profile-banner-${banner.tone}`} role="note">
          {banner.text}
        </div>
      ) : null}
      <div className="workspace-bar">
        <button type="button" className="btn btn-link" onClick={onBack}>
          ← Servers
        </button>
        <span className="workspace-title">{name}</span>
        <ConnectionStatus
          user={user}
          serverName={name}
          discovery={discovery}
          phase={phase}
          database={session && !lost ? session.database : null}
          target={switchingTo || opening}
        />
      </div>
      {bar ? <div className="workspace-session">{bar}</div> : null}
      <Splitter
        direction="horizontal"
        initial={25}
        min={15}
        max={60}
        label="Resize object explorer"
        first={
          <aside className="pane pane-explorer" aria-label="Object explorer">
            {session ? (
              <ObjectExplorer key={session.id} sessionId={session.id} onInsert={(text) => controllerRef.current?.insert(text)} />
            ) : (
              <ConnectPanel profile={profile} discovery={discovery} lastDatabase={lastDatabase} onConnected={connected} onConnecting={setOpening} />
            )}
          </aside>
        }
        second={
          <Splitter
            direction="vertical"
            initial={45}
            min={20}
            max={80}
            label="Resize editor and results"
            first={
              <section className="pane pane-editor" aria-label="SQL editor">
                <EditorPane session={session} executionEnabled={executionEnabled} execution={execution} controllerRef={controllerRef} initialDoc={carryDoc} />
              </section>
            }
            second={
              <section className="pane pane-results" aria-label="Results">
                <ResultView execution={execution} onCancel={execution.cancel} onRefresh={execution.refresh} onShowPosition={showPosition} />
              </section>
            }
          />
        }
      />
      <ConfirmDialog
        open={Boolean(execution.confirm)}
        title="Run this statement?"
        confirmLabel="Run it"
        danger
        onConfirm={execution.confirmRun}
        onCancel={execution.dismissConfirm}
      >
        <p>This statement {describeReasons(execution.confirm?.reasons).join(' and ')}.</p>
        <p className="muted">This check is a safeguard against mistakes, not a security control: your PostgreSQL privileges decide what it may do.</p>
      </ConfirmDialog>
    </section>
  );
}
