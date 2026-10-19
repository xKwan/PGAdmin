import { useState } from 'react';
import { EmptyState, StatusBadge } from '../../components/common/index.jsx';

const OUTCOME = {
  success: ['ok', 'Completed'],
  error: ['danger', 'Failed'],
  refused: ['neutral', 'Not run'],
  cancelled: ['warn', 'Cancelled'],
  timeout: ['danger', 'Time limit'],
  stopped: ['warn', 'Stopped'],
  unknown: ['danger', 'Outcome unknown'],
};

function time(iso) {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleTimeString('en-GB');
}

/** The first non-empty line, shortened for the list (the full text is in the tooltip and is what gets inserted). */
function summary(text) {
  const line = text.split('\n').map((l) => l.trim()).find((l) => l) || '(empty)';
  return line.length > 120 ? `${line.slice(0, 117)}…` : line;
}

/**
 * Query history (admin Phase 2): this browser tab's statements and scripts, newest first, with where they ran and how
 * they ended. Insert puts the text at the editor's cursor; Open in a new tab opens it in its own editor tab. Nothing
 * here runs anything. Kept in sessionStorage only (see queryHistory.js).
 */
export default function HistoryPanel({ entries, onInsert, onOpen, onClear }) {
  const [filter, setFilter] = useState('');
  const shown = filter ? entries.filter((e) => e.text.toLowerCase().includes(filter.toLowerCase())) : entries;
  return (
    <section className="history" aria-label="Query history">
      <div className="history-toolbar">
        <label className="history-filter">
          <span className="visually-hidden">Filter history</span>
          <input type="search" placeholder="Filter history" value={filter} onChange={(e) => setFilter(e.target.value)} />
        </label>
        <span className="muted">Kept in this browser tab only; closing the tab clears it.</span>
        <button type="button" className="btn" onClick={onClear} disabled={entries.length === 0}>
          Clear history
        </button>
      </div>
      {entries.length === 0 ? <EmptyState title="No history yet">Statements and scripts you run appear here.</EmptyState> : null}
      {entries.length > 0 && shown.length === 0 ? <p className="muted">Nothing matches the filter.</p> : null}
      <ol className="history-list">
        {shown.map((e) => {
          const [tone, label] = OUTCOME[e.outcome] || ['neutral', e.outcome];
          return (
            <li key={e.id} className="history-item">
              <div className="history-meta">
                <span className="muted">{time(e.at)}</span>
                <span className="muted">{e.database}</span>
                {e.script ? <StatusBadge tone="info">Script{e.statements ? ` (${e.statements})` : ''}</StatusBadge> : null}
                <StatusBadge tone={tone}>{label}</StatusBadge>
                {typeof e.durationMs === 'number' ? <span className="muted">{e.durationMs} ms</span> : null}
              </div>
              <code className="history-text" title={e.text}>
                {summary(e.text)}
              </code>
              <div className="history-actions">
                <button type="button" className="btn btn-link" onClick={() => onInsert(e.text)}>
                  Insert
                </button>
                <button type="button" className="btn btn-link" onClick={() => onOpen(e.text)}>
                  Open in a new tab
                </button>
              </div>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
