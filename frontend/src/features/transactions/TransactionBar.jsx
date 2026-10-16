import { StatusBadge } from '../../components/common/index.jsx';

const ISOLATION = [
  ['', 'Server default'],
  ['read committed', 'Read committed'],
  ['repeatable read', 'Repeatable read'],
  ['serializable', 'Serializable'],
];

function time(iso) {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
}

/** The warning before the server ends the session or rolls back the transaction (plan section 3.6). */
export function LifetimeWarning({ lifetime, transaction }) {
  if (!lifetime?.warning) return null;
  const at = time(lifetime.closesAt);
  let text;
  if (lifetime.closeReason === 'max_transaction_age') {
    text = `This transaction reaches the maximum age at ${at}. It will then be rolled back and the session closed. Commit or roll back before then.`;
  } else if (lifetime.closeReason === 'idle_in_transaction' || transaction !== 'IDLE') {
    text = `The open transaction has been idle. At ${at} it will be rolled back and the session closed. Run a statement, commit or roll back.`;
  } else {
    text = `This session has been idle and will be closed at ${at}.`;
  }
  return (
    <p className="lifetime-warning" role="alert">
      {text}
    </p>
  );
}

/**
 * Transaction controls (plan Phase 9, FN-08): auto-commit or manual mode; in manual mode the next statement starts
 * a transaction with the chosen isolation level and read-only option. Commit and Rollback act on the session's
 * transaction whichever way it began (also when the user typed BEGIN). The state shown is the one PostgreSQL
 * reported through the driver.
 */
export default function TransactionBar({ execution, disabled = false }) {
  const { tx, running } = execution;
  const open = tx.state === 'OPEN';
  const failed = tx.state === 'FAILED';
  const busy = disabled || running;
  let badge = <StatusBadge tone="neutral">No transaction</StatusBadge>;
  if (open) badge = <StatusBadge tone="warn">Transaction open</StatusBadge>;
  if (failed) badge = <StatusBadge tone="danger">Transaction failed</StatusBadge>;
  return (
    <div className="tx-bar" aria-label="Transaction">
      {badge}
      {tx.begunWith && (open || failed) ? (
        <span className="muted">
          {tx.begunWith.isolation}
          {tx.begunWith.readOnly ? ', read-only' : ''}
        </span>
      ) : null}
      <label className="tx-field">
        <input type="checkbox" checked={tx.mode === 'manual'} disabled={busy} onChange={(e) => execution.setMode(e.target.checked ? 'manual' : 'auto')} />{' '}
        Manual commit
      </label>
      {tx.mode === 'manual' && !open && !failed ? (
        <>
          <label className="tx-field">
            Isolation{' '}
            <select value={tx.isolation} disabled={busy} onChange={(e) => execution.setIsolation(e.target.value)}>
              {ISOLATION.map(([v, label]) => (
                <option key={v || 'default'} value={v}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label className="tx-field" title="A convenience, not a security control: your privileges decide what you can change">
            <input type="checkbox" checked={tx.readOnly} disabled={busy} onChange={(e) => execution.setReadOnly(e.target.checked)} />{' '}
            Read-only (convenience)
          </label>
          <button type="button" className="btn" disabled={busy} onClick={execution.begin}>
            Begin
          </button>
        </>
      ) : null}
      {open || failed ? (
        <>
          <button type="button" className="btn btn-primary" disabled={busy || failed} onClick={execution.commit} title={failed ? 'A failed transaction can only be rolled back' : undefined}>
            Commit
          </button>
          <button type="button" className="btn btn-danger" disabled={busy} onClick={execution.rollback}>
            Rollback
          </button>
        </>
      ) : null}
    </div>
  );
}
