import { describeCancelOutcome, describeExecutionError, describeScriptStop, explainSqlstate } from '../../api/execution.js';
import { EmptyState, StatusBadge } from '../../components/common/index.jsx';
import PlanView, { parsePlan } from './PlanView.jsx';
import ResultGrid from './ResultGrid.jsx';

const TX_LABEL = { IDLE: null, OPEN: 'Transaction open', FAILED: 'Transaction failed: roll back to continue' };

function seconds(ms) {
  return `${(ms / 1000).toFixed(1)} s`;
}

function time(iso) {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleTimeString('en-GB');
}

/** The statement is running: elapsed time, Cancel, whether it is waiting for a lock, and a script's progress. */
function Running({ elapsedMs, lockWait, waitEvent, cancelling, onCancel, progress, script }) {
  return (
    <div className="result-running" role="status" aria-live="polite">
      <span className="spinner-dot" aria-hidden="true" />
      <span>
        {script ? 'Running script' : 'Running'}
        {progress ? `: statement ${progress.statement} of ${progress.of}` : ''}… {seconds(elapsedMs)}
      </span>
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
      {typeof error.details?.statementIndex === 'number' ? <p className="muted">Nothing in the script was run.</p> : null}
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

function Outcome({ result, onShowPosition, paging = null }) {
  switch (result.kind) {
    case 'rows':
      return result.results.map((r, i) => {
        const grid = (
          <ResultGrid
            key={`${result.executionId}-${i}`}
            result={r}
            onLoadMore={i === 0 ? paging?.onLoadMore : null}
            loadingMore={Boolean(paging?.loading)}
            onRunReadOnly={i === 0 ? paging?.onRunReadOnly : null}
          />
        );
        const plan = result.statementClass === 'explain' ? parsePlan(r) : null;
        return plan ? <PlanView key={`${result.executionId}-${i}`} plan={plan} raw={grid} /> : grid;
      });
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

const STATUS = {
  rows: ['ok', 'Completed'],
  command: ['ok', 'Completed'],
  error: ['danger', 'Failed'],
  timeout: ['danger', 'Time limit reached'],
  cancelled: ['warn', 'Cancelled'],
  indeterminate: ['danger', 'Outcome unknown'],
  not_run: ['neutral', 'Not run'],
};

/**
 * A script's result (EXE-03): one entry per statement in order, each with its own outcome; the statement that stopped
 * the script and the ones after it are marked. "Show in editor" selects the statement's text.
 */
function ScriptResult({ result, onShowPosition, onSelect }) {
  const { statements, total, completed, stopReason, stoppedAt } = result;
  return (
    <div className="script-result">
      <p className={stopReason ? 'result-notice' : 'result-command'} role="status">
        {stopReason
          ? `Stopped at statement ${stoppedAt + 1} of ${total}: ${describeScriptStop(stopReason)}. ${completed} completed before it; the rest were not run.`
          : `All ${total} ${total === 1 ? 'statement' : 'statements'} completed.`}
      </p>
      <ol className="script-statements" aria-label="Script statements">
        {statements.map((st) => {
          const [tone, label] = STATUS[st.kind] || ['neutral', st.kind];
          return (
            <li key={st.index} className="script-statement">
              <div className="script-statement-head">
                <strong>Statement {st.index + 1}</strong>
                <span className="muted">{st.statementClass}</span>
                <StatusBadge tone={tone}>{label}</StatusBadge>
                {typeof st.durationMs === 'number' ? <span className="muted">{st.durationMs} ms</span> : null}
                <button type="button" className="btn btn-link" onClick={() => onSelect(st.offset, st.length)} aria-label={`Show statement ${st.index + 1} in the editor`}>
                  Show in editor
                </button>
              </div>
              {st.kind === 'not_run' ? (
                <p className="muted">{st.error ? st.error.message : 'Not run.'}</p>
              ) : (
                <Outcome result={st} onShowPosition={(position) => onShowPosition(position, st.offset)} />
              )}
            </li>
          );
        })}
      </ol>
    </div>
  );
}

/**
 * The results pane (plan Phase 8; admin Phase 2): running state with Cancel, a lock-wait indicator and a script's
 * progress, every outcome kind of docs/sql-execution-behaviour.md §4, a script's statement-by-statement results,
 * further pages of a large result, "retrieved at" and Refresh (runs the same text again). `source` names the editor
 * tab the text came from. `onShowPosition(position, offset)` and `onSelect(offset, length)` are relative to the text
 * that was run.
 */
export default function ResultView({ execution, onCancel, onRefresh, onShowPosition, onSelect = () => {}, source = null }) {
  const { running, result, error, elapsedMs, lockWait, waitEvent, cancelling, lastText, lastFrom, lastScript, tx, progress } = execution;
  const snapshot = tx?.begunWith && tx.state === 'OPEN' && /repeatable|serializable/.test(tx.begunWith.isolation);
  const showAt = (position, offset = 0) => onShowPosition(position, offset);
  if (running) {
    return <Running elapsedMs={elapsedMs} lockWait={lockWait} waitEvent={waitEvent} cancelling={cancelling} onCancel={onCancel} progress={progress} script={lastScript} />;
  }
  if (error) return <Refused error={error} onShowPosition={showAt} />;
  if (!result) return <EmptyState title="No results yet">Run a statement with Ctrl+Enter, or every statement with Run script.</EmptyState>;
  const txLabel = TX_LABEL[result.transaction];
  const first = result.results?.[0];
  const paging = {
    onLoadMore: first?.more ? execution.loadMore : null,
    loading: execution.fetching,
    onRunReadOnly:
      !lastScript && result.transaction === 'IDLE' && first?.truncatedBy === 'rows' && lastText && execution.runInReadOnlyTransaction
        ? () => execution.runInReadOnlyTransaction(lastText, lastFrom)
        : null,
  };
  return (
    <div className="result-view">
      <div className="result-toolbar">
        <span className="muted">
          {source ? `${source} · ` : ''}Retrieved at {time(result.retrievedAt)} · {result.durationMs} ms · {result.statementClass}
        </span>
        {txLabel ? <StatusBadge tone={result.transaction === 'FAILED' ? 'danger' : 'warn'}>{txLabel}</StatusBadge> : null}
        {result.sessionEnded ? <StatusBadge tone="danger">Session closed</StatusBadge> : null}
        <button type="button" className="btn" onClick={onRefresh} disabled={!lastText || result.sessionEnded} title="Run the same text again">
          Refresh
        </button>
      </div>
      {result.notice ? (
        <p className="result-notice" role="status">
          {result.notice}
        </p>
      ) : null}
      {snapshot ? <p className="muted result-snapshot">Inside this {tx.begunWith.isolation} transaction, Refresh shows the same snapshot.</p> : null}
      {execution.fetching ? (
        <p className="result-running" role="status">
          Loading more rows…{' '}
          <button type="button" className="btn btn-danger" onClick={onCancel} disabled={cancelling}>
            {cancelling ? 'Cancelling…' : 'Cancel'}
          </button>
        </p>
      ) : null}
      {execution.fetchError ? (
        <p className="result-error" role="alert">
          {describeExecutionError(execution.fetchError)}
        </p>
      ) : null}
      {result.kind === 'script' ? (
        <ScriptResult result={result} onShowPosition={showAt} onSelect={onSelect} />
      ) : (
        <Outcome result={result} onShowPosition={showAt} paging={paging} />
      )}
    </div>
  );
}
