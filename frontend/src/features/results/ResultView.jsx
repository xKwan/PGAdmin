import { describeCancelOutcome, describeExecutionError, explainSqlstate } from '../../api/execution.js';
import { EmptyState, StatusBadge } from '../../components/common/index.jsx';
import ResultGrid from './ResultGrid.jsx';

const TX_LABEL = { IDLE: null, OPEN: 'Transaction open', FAILED: 'Transaction failed: roll back to continue' };

function seconds(ms) {
  return `${(ms / 1000).toFixed(1)} s`;
}

function time(iso) {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleTimeString('en-GB');
}

/** The statement is running: elapsed time, Cancel, and whether it is waiting for a lock. */
function Running({ elapsedMs, lockWait, waitEvent, cancelling, onCancel }) {
  return (
    <div className="result-running" role="status" aria-live="polite">
      <span className="spinner-dot" aria-hidden="true" />
      <span>Running… {seconds(elapsedMs)}</span>
      {lockWait ? (
        <StatusBadge tone="warn" title={waitEvent ? `Waiting on ${waitEvent}` : undefined}>
          Waiting for a lock held by another session
        </StatusBadge>
      ) : null}
      <button type="button" className="btn btn-danger" onClick={onCancel} disabled={cancelling}>
        {cancelling ? 'Cancelling…' : 'Cancel'}
      </button>
    </div>
  );
}

/** A refusal before anything ran (gate, kill switch, busy, audit down). */
function Refused({ error, onShowPosition }) {
  const position = error.details?.position;
  return (
    <div className="result-error" role="alert">
      <h3>Not run</h3>
      <p>{describeExecutionError(error)}</p>
      {position ? (
        <button type="button" className="btn btn-link" onClick={() => onShowPosition(position)}>
          Show position {position} in the editor
        </button>
      ) : null}
      {error.requestId ? <p className="muted">Reference {error.requestId}</p> : null}
    </div>
  );
}

function ServerError({ error, onShowPosition }) {
  return (
    <div className="result-error" role="alert">
      <h3>
        PostgreSQL error <code>{error.sqlstate}</code>
      </h3>
      {explainSqlstate(error.sqlstate, error) ? <p className="result-error-explain">{explainSqlstate(error.sqlstate, error)}</p> : null}
      <p className="result-error-message">{error.message}</p>
      {error.detail ? <p>Detail: {error.detail}</p> : null}
      {error.hint ? <p>Hint: {error.hint}</p> : null}
      {error.position ? (
        <button type="button" className="btn btn-link" onClick={() => onShowPosition(error.position)}>
          Show position {error.position} in the editor
        </button>
      ) : null}
    </div>
  );
}

function Stopped({ result }) {
  const outcome = describeCancelOutcome(result.cancelOutcome);
  return (
    <div className="result-stopped" role="alert">
      <h3>{result.kind === 'timeout' ? 'Stopped: the time limit was reached' : 'Cancelled'}</h3>
      {outcome ? <p>{outcome}</p> : null}
    </div>
  );
}

/** "Completed.", or "Completed. 7 rows affected." when PostgreSQL reported a count. */
export function commandSummary(results) {
  const counts = results.filter((r) => typeof r.rowCount === 'number').map((r) => r.rowCount);
  if (counts.length === 0) return 'Completed.';
  const unit = counts.length === 1 && counts[0] === 1 ? 'row' : 'rows';
  return `Completed. ${counts.join(', ')} ${unit} affected.`;
}

function Outcome({ result, onShowPosition }) {
  switch (result.kind) {
    case 'rows':
      return result.results.map((r, i) => <ResultGrid key={`${result.executionId}-${i}`} result={r} />);
    case 'command':
      return (
        <p className="result-command" role="status">
          {commandSummary(result.results)}
        </p>
      );
    case 'error':
      return <ServerError error={result.error} onShowPosition={onShowPosition} />;
    case 'timeout':
    case 'cancelled':
      return <Stopped result={result} />;
    case 'indeterminate':
      return (
        <div className="result-stopped" role="alert">
          <h3>Outcome unknown</h3>
          <p>{result.message} Check the data before running the statement again.</p>
        </div>
      );
    default:
      return <p>Unrecognised result.</p>;
  }
}

/**
 * The results pane (plan Phase 8): running state with Cancel and a lock-wait indicator, every outcome kind of
 * docs/sql-execution-behaviour.md §4, "retrieved at" and Refresh (runs the same text again).
 */
export default function ResultView({ execution, onCancel, onRefresh, onShowPosition }) {
  const { running, result, error, elapsedMs, lockWait, waitEvent, cancelling, lastText, tx } = execution;
  const snapshot = tx?.begunWith && tx.state === 'OPEN' && /repeatable|serializable/.test(tx.begunWith.isolation);
  if (running) return <Running elapsedMs={elapsedMs} lockWait={lockWait} waitEvent={waitEvent} cancelling={cancelling} onCancel={onCancel} />;
  if (error) return <Refused error={error} onShowPosition={onShowPosition} />;
  if (!result) return <EmptyState title="No results yet">Run a statement with Ctrl+Enter.</EmptyState>;
  const txLabel = TX_LABEL[result.transaction];
  return (
    <div className="result-view">
      <div className="result-toolbar">
        <span className="muted">
          Retrieved at {time(result.retrievedAt)} · {result.durationMs} ms · {result.statementClass}
        </span>
        {txLabel ? <StatusBadge tone={result.transaction === 'FAILED' ? 'danger' : 'warn'}>{txLabel}</StatusBadge> : null}
        {result.sessionEnded ? <StatusBadge tone="danger">Session closed</StatusBadge> : null}
        <button type="button" className="btn" onClick={onRefresh} disabled={!lastText || result.sessionEnded} title="Run the same statement again">
          Refresh
        </button>
      </div>
      {result.notice ? (
        <p className="result-notice" role="status">
          {result.notice}
        </p>
      ) : null}
      {snapshot ? <p className="muted result-snapshot">Inside this {tx.begunWith.isolation} transaction, Refresh shows the same snapshot.</p> : null}
      <Outcome result={result} onShowPosition={onShowPosition} />
    </div>
  );
}
