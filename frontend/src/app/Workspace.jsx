import { useState } from 'react';
import { EmptyState, StatusBadge } from '../components/common/index.jsx';
import { Splitter } from '../components/common/Splitter.jsx';
import ConnectPanel from '../features/connections/ConnectPanel.jsx';
import SessionBar from '../features/connections/SessionBar.jsx';

const NAMES = { internal: 'Internal PostgreSQL', external: 'External PostgreSQL' };

/**
 * The DBeaver-style workspace: object explorer on the left, editor above results on the right. Connecting is
 * Phase 6; the editor and results arrive in Phase 8 and the object tree in Phase 10.
 */
export default function Workspace({ profile, onBack }) {
  const [session, setSession] = useState(null);
  return (
    <section className="workspace" aria-label={`${NAMES[profile] || profile} workspace`}>
      <div className="workspace-bar">
        <button type="button" className="btn btn-link" onClick={onBack}>
          ← Servers
        </button>
        <span className="workspace-title">{NAMES[profile] || profile}</span>
        {session ? <SessionBar session={session} onClosed={() => setSession(null)} /> : <StatusBadge tone="neutral">Not connected</StatusBadge>}
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
              <ConnectPanel profile={profile} onConnected={setSession} />
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
                <EmptyState title="SQL editor">{session ? 'The SQL editor arrives in a later version.' : 'Available once you are connected.'}</EmptyState>
              </section>
            }
            second={
              <section className="pane pane-results" aria-label="Results">
                <EmptyState title="No results yet" />
              </section>
            }
          />
        }
      />
    </section>
  );
}
