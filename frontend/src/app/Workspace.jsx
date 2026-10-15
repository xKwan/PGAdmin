import { EmptyState, StatusBadge } from '../components/common/index.jsx';
import { Splitter } from '../components/common/Splitter.jsx';

const NAMES = { internal: 'Internal PostgreSQL', external: 'External PostgreSQL' };

/**
 * The DBeaver-style workspace: object explorer on the left, editor above results on the right. The panes are
 * filled in by later phases (connections P6, editor and results P8, metadata P10).
 */
export default function Workspace({ profile, onBack }) {
  return (
    <section className="workspace" aria-label={`${NAMES[profile] || profile} workspace`}>
      <div className="workspace-bar">
        <button type="button" className="btn btn-link" onClick={onBack}>
          ← Servers
        </button>
        <span className="workspace-title">{NAMES[profile] || profile}</span>
        <StatusBadge tone="neutral">Not connected</StatusBadge>
      </div>
      <Splitter
        direction="horizontal"
        initial={25}
        min={15}
        max={60}
        label="Resize object explorer"
        first={
          <aside className="pane pane-explorer" aria-label="Object explorer">
            <EmptyState title="No connection">Connect to a database to browse its objects.</EmptyState>
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
                <EmptyState title="SQL editor">Available once you are connected.</EmptyState>
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
