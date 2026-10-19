import { useCallback, useEffect, useRef, useState } from 'react';
import { describeReasons } from '../api/execution.js';
import { listProfiles } from '../api/dbsessions.js';
import { ConfirmDialog, StatusBadge } from '../components/common/index.jsx';
import { Splitter } from '../components/common/Splitter.jsx';
import ConnectPanel from '../features/connections/ConnectPanel.jsx';
import ConnectionStatus from '../features/connections/ConnectionStatus.jsx';
import { getLastDatabase, setLastDatabase } from '../features/connections/lastDatabase.js';
import useServerDiscovery from '../features/connections/useServerDiscovery.js';
import EditorArea from '../features/editor/EditorArea.jsx';
import useEditorTabs from '../features/editor/useEditorTabs.js';
import ObjectExplorer from '../features/explorer/ObjectExplorer.jsx';
import SessionBar, { LostSession } from '../features/connections/SessionBar.jsx';
import HistoryPanel from '../features/history/HistoryPanel.jsx';
import { addHistory, clearHistory, historyEntry, readHistory } from '../features/history/queryHistory.js';
import ResultView from '../features/results/ResultView.jsx';
import useExecution from '../features/results/useExecution.js';
import TransactionBar, { LifetimeWarning } from '../features/transactions/TransactionBar.jsx';

const NAMES = { internal: 'Internal PostgreSQL', external: 'External PostgreSQL' };

/** Statement classes after which the object explorer is out of date once they are committed. */
const CHANGES_OBJECTS = new Set(['ddl', 'dcl']);

/** True if the result (a statement or a script) changed objects or privileges successfully. */
function changedObjects(result) {
  if (result.kind === 'script') {
    return result.statements.some((st) => CHANGES_OBJECTS.has(st.statementClass) && (st.kind === 'command' || st.kind === 'rows'));
  }
  return CHANGES_OBJECTS.has(result.statementClass) && (result.kind === 'command' || result.kind === 'rows');
}

/**
 * The editor's toolbar: Run (the selection or the statement at the cursor) and Run script (every statement, one at a
 * time, stopping at the first failure), the execution mode, transaction controls, then the editor tabs. Without a
 * session or with execution switched off, SQL can still be written, opened and saved; nothing can be run.
 */
function EditorPane({ session, executionEnabled, execution, tabs, controllers, onRun, onRunScript }) {
  const failed = execution.tx.state === 'FAILED';
  const canRun = Boolean(session) && executionEnabled && !execution.running && !execution.fetching && !failed;
  return (
    <>
      <div className="editor-toolbar">
        <button type="button" className="btn btn-primary" onClick={() => controllers.current[tabs.active.id]?.run()} disabled={!canRun}>
          Run
        </button>
        <button
          type="button"
          className="btn"
          onClick={onRunScript}
          disabled={!canRun}
          title="Runs every statement in this tab (or in the selection), one at a time, and stops at the first one that fails"
        >
          Run script
        </button>
        <span className="muted">Ctrl+Enter runs the selection, or the statement at the cursor. Run script runs them all.</span>
        {executionEnabled ? null : <StatusBadge tone="warn">Running SQL is switched off by the administrator</StatusBadge>}
        {executionEnabled && !session ? <StatusBadge tone="neutral">Connect to a database to run SQL</StatusBadge> : null}
        {failed ? <StatusBadge tone="danger">The transaction failed: roll back to continue</StatusBadge> : null}
      </div>
      {session && executionEnabled ? <TransactionBar execution={execution} /> : null}
      {session ? <LifetimeWarning lifetime={execution.tx.lifetime} transaction={execution.tx.state} /> : null}
      <EditorArea tabs={tabs} controllers={controllers} canRun={canRun} onRun={onRun} />
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

/** Asks the browser to warn before the page is closed while editor tabs have unsaved changes. */
function useUnsavedWarning(active) {
  useEffect(() => {
    if (!active) return undefined;
    const warn = (e) => {
      e.preventDefault();
      e.returnValue = ''; // older browsers need it set
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [active]);
}

const CLOSED_BY_SERVER = 'The database session was closed by the server. Anything not committed was rolled back.';

/**
 * The DBeaver-style workspace: object explorer on the left, editor tabs above results and history on the right.
 *
 * Connection state (admin Phase 1): server discovery runs as soon as the workspace opens and is the evidence that the
 * PostgreSQL server is reachable for this user; a database session exists only once the server has confirmed it
 * (open, switch or reconnect). No session is opened automatically: each one is audited and counts against the
 * per-user limit, so the user chooses (the last database used is offered first). `user` is /api/me's user.
 *
 * Editor (admin Phase 2): tabs with local .sql files belong to the workspace, not to a session, so they survive
 * switching database and reconnecting; unsaved changes are confirmed before a tab or the workspace is left.
 */
export default function Workspace({ profile, onBack, executionEnabled = false, user = null }) {
  const [session, setSession] = useState(null);
  const [lost, setLost] = useState(null);
  const [opening, setOpening] = useState(null);
  const [switchingTo, setSwitchingTo] = useState(null);
  const [lastDatabase, setLast] = useState(() => getLastDatabase(profile, user?.sid));
  const [history, setHistory] = useState(() => readHistory(profile, user?.sid));
  const [lowerTab, setLowerTab] = useState('results');
  const [resultTab, setResultTab] = useState(null);
  const [explorerVersion, setExplorerVersion] = useState(0);
  const [leaving, setLeaving] = useState(false);
  const pendingObjects = useRef(false);
  const controllers = useRef({});
  const switchTo = useRef(null);
  const tabs = useEditorTabs();
  const discovery = useServerDiscovery(profile);
  const database = session?.database;

  const recordHistory = useCallback(
    ({ text, script, result, error }) => {
      setHistory(addHistory(profile, user?.sid, historyEntry({ text, database, script, result, error })));
    },
    [profile, user?.sid, database],
  );
  /** The explorer shows committed objects: refresh once a change to objects is committed (or auto-committed). */
  const observeResult = useCallback((result) => {
    if (changedObjects(result)) pendingObjects.current = true;
    if (pendingObjects.current && result.transaction === 'IDLE') {
      pendingObjects.current = false;
      setExplorerVersion((v) => v + 1);
    }
  }, []);
  const execution = useExecution(session?.id, { onSessionEnded: () => setLost(CLOSED_BY_SERVER), onFinished: recordHistory, onResult: observeResult });
  const banner = useProfileBanner(profile);
  const name = NAMES[profile] || profile;
  useUnsavedWarning(tabs.anyDirty);

  const connected = (s) => {
    setLost(null);
    setOpening(null);
    setSession(s);
    setLastDatabase(profile, user?.sid, s.database);
    setLast(s.database);
  };
  const disconnected = () => {
    setLost(null);
    setSession(null);
    discovery.refresh();
  };
  const sessionLost = (message) => {
    setLost(message);
    discovery.refresh();
  };

  const runFrom = (tabId, text, from) => {
    setResultTab(tabId);
    setLowerTab('results');
    execution.run(text, from);
  };
  const runScript = () => {
    const c = controllers.current[tabs.active.id];
    if (!c) return;
    const { text, from } = c.script();
    setResultTab(tabs.active.id);
    setLowerTab('results');
    execution.runScript(text, from);
  };
  /** The editor the shown result came from (it may since have been closed). */
  const resultEditor = () => {
    const id = resultTab && controllers.current[resultTab] ? resultTab : null;
    if (id) tabs.activate(id);
    return id ? controllers.current[id] : null;
  };
  const showPosition = (position, offset = 0) => resultEditor()?.showPosition(execution.lastFrom + offset, position);
  const selectRun = (offset, length) => {
    const start = execution.lastFrom + offset;
    resultEditor()?.select(start, start + length);
  };
  const insert = (text) => controllers.current[tabs.active.id]?.insert(text);
  const back = () => (tabs.anyDirty ? setLeaving(true) : onBack());

  let phase = opening ? 'opening' : 'none';
  if (session) phase = lost ? 'lost' : switchingTo ? 'switching' : 'open';

  let bar = null;
  if (session && lost) {
    bar = <LostSession profile={profile} database={session.database} message={lost} onReconnected={connected} onChooseAnother={disconnected} />;
  } else if (session) {
    bar = (
      <SessionBar
        session={session}
        profile={profile}
        discovery={discovery}
        onClosed={disconnected}
        onLost={sessionLost}
        onSwitched={connected}
        onSwitching={setSwitchingTo}
        onHeartbeat={execution.observeHeartbeat}
        transactionOpen={execution.tx.state !== 'IDLE'}
        busy={execution.running || execution.fetching}
        ref={switchTo}
      />
    );
  }
  const source = tabs.tabs.find((t) => t.id === resultTab)?.title ?? null;

  return (
    <section className="workspace" aria-label={`${name} workspace`}>
      {banner ? (
        <div className={`profile-banner profile-banner-${banner.tone}`} role="note">
          {banner.text}
        </div>
      ) : null}
      <div className="workspace-bar">
        <button type="button" className="btn btn-link" onClick={back}>
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
              <ObjectExplorer
                key={session.id}
                sessionId={session.id}
                database={session.database}
                databases={discovery.databases}
                refreshKey={explorerVersion}
                onInsert={insert}
                onSwitch={lost ? null : (db) => switchTo.current?.switchTo(db)}
              />
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
                <EditorPane
                  session={session}
                  executionEnabled={executionEnabled}
                  execution={execution}
                  tabs={tabs}
                  controllers={controllers}
                  onRun={runFrom}
                  onRunScript={runScript}
                />
              </section>
            }
            second={
              <section className="pane pane-results" aria-label="Results">
                <div role="tablist" aria-label="Results and history" className="tab-strip lower-tabs">
                  <button type="button" role="tab" aria-selected={lowerTab === 'results'} className="tab-button" onClick={() => setLowerTab('results')}>
                    Results
                  </button>
                  <button type="button" role="tab" aria-selected={lowerTab === 'history'} className="tab-button" onClick={() => setLowerTab('history')}>
                    History ({history.length})
                  </button>
                </div>
                {lowerTab === 'results' ? (
                  <ResultView
                    execution={execution}
                    onCancel={execution.cancel}
                    onRefresh={execution.refresh}
                    onShowPosition={showPosition}
                    onSelect={selectRun}
                    source={tabs.tabs.length > 1 ? source : null}
                  />
                ) : (
                  <HistoryPanel
                    entries={history}
                    onInsert={insert}
                    onOpen={(text) => tabs.open(text)}
                    onClear={() => {
                      clearHistory(profile, user?.sid);
                      setHistory([]);
                    }}
                  />
                )}
              </section>
            }
          />
        }
      />
      <ConfirmDialog
        open={Boolean(execution.confirm)}
        title={execution.confirm?.script ? 'Run this script?' : 'Run this statement?'}
        confirmLabel={execution.confirm?.script ? 'Run the script' : 'Run it'}
        danger
        onConfirm={execution.confirmRun}
        onCancel={execution.dismissConfirm}
      >
        <p>
          This {execution.confirm?.script ? 'script' : 'statement'} {describeReasons(execution.confirm?.reasons).join(' and ')}.
        </p>
        <p className="muted">This check is a safeguard against mistakes, not a security control: your PostgreSQL privileges decide what it may do.</p>
      </ConfirmDialog>
      <ConfirmDialog
        open={leaving}
        title="Leave with unsaved changes?"
        confirmLabel="Leave and discard changes"
        danger
        onConfirm={() => {
          setLeaving(false);
          onBack();
        }}
        onCancel={() => setLeaving(false)}
      >
        Some editor tabs have changes that are not saved to a file. Leaving the workspace discards them.
      </ConfirmDialog>
    </section>
  );
}
