import { useCallback, useEffect, useId, useState } from 'react';
import { decideApproval, listApprovals, requestApproval, withdrawApproval } from '../../api/approvals.js';
import { describeReasons } from '../../api/execution.js';
import { ConfirmDialog, Spinner, StatusBadge } from '../../components/common/index.jsx';

// Two-person approval (admin Phase 14, decision A19). Some statements (the profile lists which: for example DROP and
// TRUNCATE) run only after a second person, from the approver group, approved exactly that text. The server enforces
// it; these panels only ask, show and decide.

const STATE = {
  pending: ['Waiting for a second person', 'warn'],
  approved: ['Approved', 'ok'],
  rejected: ['Rejected', 'danger'],
  used: ['Used', 'neutral'],
  expired: ['Expired', 'neutral'],
  withdrawn: ['Withdrawn', 'neutral'],
};

function when(iso) {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleTimeString('en-GB');
}

function State({ state }) {
  const [label, tone] = STATE[state] || [state, 'neutral'];
  return <StatusBadge tone={tone}>{label}</StatusBadge>;
}

/**
 * Shown when a statement or script needs approval. `need` is {text, script, reasons}; `onRunAgain` runs the same text
 * again (after approval); `onDismiss` hides the panel.
 */
export function ApprovalNeeded({ sessionId, need, onRunAgain, onDismiss }) {
  const [request, setRequest] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const ask = async () => {
    setBusy(true);
    setError(null);
    try {
      setRequest(await requestApproval(sessionId, need.text, need.script));
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };
  const check = useCallback(async () => {
    if (!request) return;
    setBusy(true);
    setError(null);
    try {
      const r = await listApprovals();
      setRequest(r.mine.find((m) => m.id === request.id) || { ...request, state: 'expired' });
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }, [request]);
  const withdraw = async () => {
    try {
      setRequest(await withdrawApproval(request.id));
    } catch (e) {
      setError(e.message);
    }
  };

  return (
    <section className="result-notice approval-needed" aria-label="Approval needed">
      <p>
        <strong>A second person must approve this before it runs.</strong> The {need.script ? 'script' : 'statement'} {describeReasons(need.reasons).join(' and ')}. Nothing has
        run.
      </p>
      {!request ? (
        <p>
          <button type="button" className="btn btn-primary" onClick={ask} disabled={busy}>
            Request approval
          </button>{' '}
          <button type="button" className="btn btn-link" onClick={onDismiss}>
            Not now
          </button>
        </p>
      ) : (
        <div>
          <p>
            <State state={request.state} /> Request made at {when(request.createdAt)}; it ends at {when(request.expiresAt)}.
            {request.decidedBy ? ` Decided by ${request.decidedBy}.` : ''}
            {request.comment ? ` Comment: ${request.comment}` : ''}
          </p>
          <p>
            {request.state === 'approved' ? (
              <button type="button" className="btn btn-primary" onClick={onRunAgain}>
                Run it now
              </button>
            ) : null}
            {request.state === 'pending' ? (
              <>
                <button type="button" className="btn" onClick={check} disabled={busy}>
                  Check again
                </button>{' '}
                <button type="button" className="btn btn-link" onClick={withdraw}>
                  Withdraw the request
                </button>
              </>
            ) : null}
            {request.state !== 'pending' && request.state !== 'approved' ? (
              <button type="button" className="btn btn-link" onClick={onDismiss}>
                Close
              </button>
            ) : null}
          </p>
          <p className="muted">Approvers see this request under Approvals on their start page. The approval is for this exact text, in this session, once.</p>
        </div>
      )}
      {error ? (
        <p className="result-error" role="alert">
          {error}
        </p>
      ) : null}
    </section>
  );
}

function RequestRow({ r, onDecide, onWithdraw }) {
  return (
    <li className="approval-item">
      <p>
        <State state={r.state} /> <strong>{r.requester}</strong> · {r.database} as {r.role} · {r.script ? 'script' : 'statement'} that {describeReasons(r.reasons).join(' and ')} ·
        asked at {when(r.createdAt)}
        {r.decidedBy ? ` · decided by ${r.decidedBy}` : ''}
      </p>
      <pre className="manage-sql" aria-label={`SQL of the request by ${r.requester}`}>
        {r.text}
      </pre>
      {onDecide ? (
        <p>
          <button type="button" className="btn btn-primary" onClick={() => onDecide(r, true)}>
            Approve
          </button>{' '}
          <button type="button" className="btn btn-danger" onClick={() => onDecide(r, false)}>
            Reject
          </button>
        </p>
      ) : null}
      {onWithdraw && (r.state === 'pending' || r.state === 'approved') ? (
        <p>
          <button type="button" className="btn btn-link" onClick={() => onWithdraw(r)}>
            Withdraw
          </button>
        </p>
      ) : null}
    </li>
  );
}

/** The approvals page (start page card for approvers): open requests of others, and your own. */
export function ApprovalsPage({ onBack }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [deciding, setDeciding] = useState(null);
  const [comment, setComment] = useState('');
  const [reload, setReload] = useState(0);
  const commentId = useId();
  // Stable, so the dialog keeps the focus in the comment field while typing (it focuses Cancel when this changes).
  const cancelDecision = useCallback(() => setDeciding(null), []);

  useEffect(() => {
    let live = true;
    listApprovals().then(
      (r) => live && setData(r),
      (e) => live && setError(e.message),
    );
    return () => {
      live = false;
    };
  }, [reload]);

  const decide = async () => {
    const { r, approve } = deciding;
    setDeciding(null);
    try {
      await decideApproval(r.id, approve, comment);
      setComment('');
    } catch (e) {
      setError(e.message);
    }
    setReload((n) => n + 1);
  };
  const withdraw = async (r) => {
    try {
      await withdrawApproval(r.id);
    } catch (e) {
      setError(e.message);
    }
    setReload((n) => n + 1);
  };

  return (
    <section className="audit-page" aria-labelledby="approvals-title">
      <div className="workspace-bar">
        <button type="button" className="btn btn-link" onClick={onBack}>
          ← Servers
        </button>
        <h1 id="approvals-title" className="workspace-title">
          Approvals
        </h1>
        <button type="button" className="btn" onClick={() => setReload((n) => n + 1)}>
          Refresh
        </button>
      </div>
      <p className="muted admin-note">
        Read each statement before deciding. Approving lets its author run exactly that text once, in the session they asked from, within 15 minutes; it still
        runs as their own PostgreSQL role. You can’t decide on your own requests. Every decision is recorded in the audit trail.
      </p>
      {error ? (
        <p className="result-error" role="alert">
          {error}
        </p>
      ) : null}
      {!data && !error ? <Spinner label="Loading requests" /> : null}
      {data ? (
        <>
          <h2 className="admin-heading">Waiting for your decision</h2>
          {!data.approver ? <p>You aren’t in the approver group, so you can’t decide on requests.</p> : null}
          {data.approver && data.toDecide.length === 0 ? <p>No requests are waiting.</p> : null}
          <ul className="approval-list" aria-label="Requests to decide">
            {data.toDecide.map((r) => (
              <RequestRow key={r.id} r={r} onDecide={(req, approve) => setDeciding({ r: req, approve })} />
            ))}
          </ul>
          <h2 className="admin-heading">Your requests</h2>
          {data.mine.length === 0 ? <p>You have no requests.</p> : null}
          <ul className="approval-list" aria-label="Your requests">
            {data.mine.map((r) => (
              <RequestRow key={r.id} r={r} onWithdraw={withdraw} />
            ))}
          </ul>
        </>
      ) : null}
      <ConfirmDialog
        open={Boolean(deciding)}
        title={deciding?.approve ? 'Approve this request?' : 'Reject this request?'}
        confirmLabel={deciding?.approve ? 'Approve' : 'Reject'}
        danger={Boolean(deciding?.approve)}
        onConfirm={decide}
        onCancel={cancelDecision}
      >
        <p>
          {deciding?.approve
            ? `${deciding?.r.requester} can then run this exact text once, as their own role, on ${deciding?.r.database}.`
            : `${deciding?.r.requester} can't run this text unless someone approves a new request.`}
        </p>
        <span className="manage-field">
          <label htmlFor={commentId}>Comment (optional)</label>
          <input id={commentId} value={comment} maxLength={500} onChange={(e) => setComment(e.target.value)} />
        </span>
      </ConfirmDialog>
    </section>
  );
}
