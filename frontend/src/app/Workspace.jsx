import { lazy, Suspense, useRef, useState } from 'react';
import { EmptyState, Spinner, StatusBadge } from '../components/common/index.jsx';
import { Splitter } from '../components/common/Splitter.jsx';
import ConnectPanel from '../features/connections/ConnectPanel.jsx';
import SessionBar from '../features/connections/SessionBar.jsx';
import ResultView from '../features/results/ResultView.jsx';
import useExecution from '../features/results/useExecution.js';

// The editor (CodeMirror) is its own chunk, loaded when a session opens (bundle budget, plan Phase 8).
const SqlEditor = lazy(() => import('../features/editor/SqlEditor.jsx'));

const NAMES = { internal: 'Internal PostgreSQL', external: 'External PostgreSQL' };

function EditorPane({ session, executionEnabled, execution, controllerRef }) {
  if (!session) {
    return <EmptyState title="SQL editor">Available once you are connected.</EmptyState>;
  }
  const canRun = executionEnabled && !execution.running;
  return (
    <>
      <div className="editor-toolbar">
        <button type="button" className="btn btn-primary" onClick={() => controllerRef.current?.run()} disabled={!canRun}>
          Run
        </button>
        <span className="muted">Ctrl+Enter runs the selection, or the statement at the cursor. One statement at a time.</span>
        {executionEnabled ? null : <StatusBadge tone="warn">Running SQL is switched off by the administrator</StatusBadge>}
      </div>
      <Suspense fallback={<Spinner label="Loading the editor" />}>
        <SqlEditor key={session.id} onRun={execution.run} canRun={canRun} controllerRef={controllerRef} />
      </Suspense>
    </>
  );
}

/**
 * The DBeaver-style workspace: object explorer on the left, editor above results on the right. The object tree
 * arrives in Phase 10.
 */
export default function Workspace({ profile, onBack, executionEnabled = false }) {
  const [session, setSession] = useState(null);
  const [ended, setEnded] = useState(false);
  const controllerRef = useRef(null);
  const execution = useExecution(session?.id, { onSessionEnded: () => setEnded(true) });

  const connected = (s) => {
    setEnded(false);
    setSession(s);
  };
  const disconnected = () => {
    setEnded(false);
    setSession(null);
  };
  const showPosition = (position) => controllerRef.current?.showPosition(execution.lastFrom, position);

  let bar = <StatusBadge tone="neutral">Not connected</StatusBadge>;
  if (session && ended) {
    bar = (
      <div className="session-bar session-lost" role="alert">
        <StatusBadge tone="danger">Disconnected</StatusBadge>
        <span>The database session was closed by the server.</span>
        <button type="button" className="btn" onClick={disconnected}>
          Connect again
        </button>
      </div>
    );
  } else if (session) {
    bar = <SessionBar session={session} onClosed={disconnected} />;
  }

  return (
    <section className="workspace" aria-label={`${NAMES[profile] || profile} workspace`}>
      <div className="workspace-bar">
        <button type="button" className="btn btn-link" onClick={onBack}>
          ← Servers
        </button>
        <span className="workspace-title">{NAMES[profile] || profile}</span>
        {bar}
      </div>
      <Splitter
        direction="horizontal"
        initial={25}
        min={15}
        max={60}
        label="Resize object explorer"
        first={
          <aside className="pane pane-explorer" aria-label="Object explorer">
            {session ? (
              <EmptyState title={session.database}>Object browsing arrives in a later version.</EmptyState>
            ) : (
              <ConnectPanel profile={profile} onConnected={connected} />
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
                <EditorPane session={session} executionEnabled={executionEnabled} execution={execution} controllerRef={controllerRef} />
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
    </section>
  );
}
