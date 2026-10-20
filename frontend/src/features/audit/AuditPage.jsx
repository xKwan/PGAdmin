import { useId, useState } from 'react';
import { searchAudit } from '../../api/audit.js';
import { Spinner, StatusBadge } from '../../components/common/index.jsx';

// The audit viewer (admin Phase 9, decision A14): read-only. pgweb reads the audit trail as you, so only members of
// pgweb_audit_reader on the audit database see anything; every search is itself recorded.

/** Event types pgweb writes, offered as suggestions (any type can be typed). */
export const EVENT_TYPES = [
  'app_login', 'app_logout', 'authz_denied', 'db_connect_attempt', 'db_connect_result', 'db_disconnect', 'db_companion_failed',
  'session_lost', 'session_reaped', 'exec_intent', 'exec_outcome', 'exec_rejected', 'script_outcome', 'exec_fetch', 'exec_fetch_outcome',
  'cancel_requested', 'cancel_result', 'tx_commit', 'tx_rollback', 'metadata_browse', 'session_signal', 'session_signal_outcome',
  'data_export', 'audit_view', 'audit_spool_replayed', 'audit_failure',
];
const OUTCOMES = ['', 'success', 'failure', 'refused', 'error', 'cancelled', 'timeout', 'indeterminate', 'not_sent'];

/** A datetime-local value (local time, minutes) for an instant. */
export function localValue(date) {
  const pad = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function when(iso) {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('en-GB');
}

function describe(error) {
  switch (error?.code) {
    case 'AUDIT_READ_DENIED':
      return 'Your PostgreSQL role can’t read the audit trail. Auditors are members of pgweb_audit_reader on the audit database: ask the administrator.';
    case 'CONNECT_FAILED':
      return 'pgweb could not connect to the audit database as you. Your role may have no login there: ask the administrator.';
    case 'AUDIT_UNAVAILABLE':
      return 'The audit database is not available, so searches can’t be recorded and none are run. Try again later.';
    default:
      return error?.message || 'The search failed.';
  }
}

function Field({ label, children }) {
  const id = useId();
  return (
    <span className="manage-field">
      <label htmlFor={id}>{label}</label>
      {children(id)}
    </span>
  );
}

function Details({ e }) {
  const rows = [
    ['Sequence', e.seq],
    ['Windows user', e.windowsUser],
    ['SID', e.userSid],
    ['Client address', e.clientIp],
    ['Server', e.profile],
    ['Database', e.database],
    ['PostgreSQL role', e.sessionUser],
    ['Statement class', e.statementClass],
    ['Statement (literals removed)', e.sqlNormalised],
    ['Outcome', e.outcome],
    ['SQLSTATE', e.sqlstate],
    ['Rows', e.rowCount],
    ['Duration (ms)', e.durationMs],
    ['Execution ID', e.executionId],
    ['DB session', e.dbSessionId],
    ['Request ID', e.requestId],
    ['Details', e.details],
  ].filter(([, v]) => v !== undefined && v !== null && v !== '');
  return (
    <dl className="admin-properties audit-details">
      {rows.map(([k, v]) => (
        <div key={k}>
          <dt>{k}</dt>
          <dd>{String(v)}</dd>
        </div>
      ))}
    </dl>
  );
}

/** `onBack` returns to the server list. */
export default function AuditPage({ onBack }) {
  const now = new Date();
  const [form, setForm] = useState({ from: localValue(new Date(now.getTime() - 24 * 3600 * 1000)), to: localValue(new Date(now.getTime() + 60 * 1000)), type: '', user: '', outcome: '', database: '' });
  const [state, setState] = useState({ events: null, more: false, error: null, busy: false, query: null });
  const [open, setOpen] = useState(null);
  const listId = useId();
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  const run = async (query, append) => {
    setState((s) => ({ ...s, busy: true, error: null }));
    try {
      const r = await searchAudit(query);
      setState((s) => ({ events: append ? [...(s.events || []), ...r.events] : r.events, more: r.more, error: null, busy: false, query }));
    } catch (error) {
      setState((s) => ({ ...s, busy: false, error }));
    }
  };

  const search = (e) => {
    e.preventDefault();
    const from = new Date(form.from);
    const to = new Date(form.to);
    if (Number.isNaN(from.getTime()) || Number.isNaN(to.getTime())) {
      setState((s) => ({ ...s, error: { message: 'Enter a start and an end.' } }));
      return;
    }
    setOpen(null);
    run({ from: from.toISOString(), to: to.toISOString(), type: form.type, user: form.user, outcome: form.outcome, database: form.database, limit: 100 }, false);
  };
  const older = () => run({ ...state.query, before: state.events[state.events.length - 1].seq }, true);

  return (
    <section className="audit-page" aria-labelledby="audit-title">
      <div className="workspace-bar">
        <button type="button" className="btn btn-link" onClick={onBack}>
          ← Servers
        </button>
        <h1 id="audit-title" className="workspace-title">
          Audit trail
        </h1>
        <StatusBadge tone="info">Read only</StatusBadge>
      </div>
      <p className="muted admin-note">
        pgweb reads the audit trail as you: you see it only if your PostgreSQL role is an auditor (pgweb_audit_reader). Statements are shown with their literal
        values removed. Every search is recorded in the audit trail.
      </p>
      <form className="audit-filters" onSubmit={search} aria-label="Search the audit trail">
        <Field label="From">{(id) => <input id={id} type="datetime-local" value={form.from} onChange={set('from')} required />}</Field>
        <Field label="To">{(id) => <input id={id} type="datetime-local" value={form.to} onChange={set('to')} required />}</Field>
        <Field label="Event type">
          {(id) => (
            <>
              <input id={id} list={listId} value={form.type} onChange={set('type')} placeholder="any" />
              <datalist id={listId}>
                {EVENT_TYPES.map((t) => (
                  <option key={t} value={t} />
                ))}
              </datalist>
            </>
          )}
        </Field>
        <Field label="User (account or SID)">{(id) => <input id={id} value={form.user} onChange={set('user')} placeholder="any" />}</Field>
        <Field label="Outcome">
          {(id) => (
            <select id={id} value={form.outcome} onChange={set('outcome')}>
              {OUTCOMES.map((o) => (
                <option key={o} value={o}>
                  {o || 'any'}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Database">{(id) => <input id={id} value={form.database} onChange={set('database')} placeholder="any" />}</Field>
        <button type="submit" className="btn btn-primary" disabled={state.busy}>
          Search
        </button>
      </form>
      {state.error ? (
        <div className="result-error" role="alert">
          {describe(state.error)}
        </div>
      ) : null}
      {state.busy ? <Spinner label="Searching" /> : null}
      {state.events ? (
        state.events.length === 0 ? (
          <p>No events match.</p>
        ) : (
          <>
            <table className="grid admin-table">
              <caption>Events, newest first</caption>
              <thead>
                <tr>
                  <th scope="col">Time</th>
                  <th scope="col">Event</th>
                  <th scope="col">User</th>
                  <th scope="col">Database</th>
                  <th scope="col">Statement</th>
                  <th scope="col">Outcome</th>
                  <th scope="col">
                    <span className="visually-hidden">Details</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {state.events.map((e) => (
                  <tr key={e.seq}>
                    <td>{when(e.occurredAt)}</td>
                    <td>{e.type}</td>
                    <td>{e.windowsUser}</td>
                    <td>{e.database}</td>
                    <td className="admin-query">{e.sqlNormalised || e.statementClass}</td>
                    <td>{e.outcome}</td>
                    <td>
                      <button
                        type="button"
                        className="btn btn-link"
                        aria-label={`Details of event ${e.seq}`}
                        aria-expanded={open === e.seq}
                        onClick={() => setOpen(open === e.seq ? null : e.seq)}
                      >
                        {open === e.seq ? 'Hide' : 'Details'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {open !== null ? (
              <section aria-label={`Event ${open}`}>
                <Details e={state.events.find((x) => x.seq === open)} />
              </section>
            ) : null}
            {state.more ? (
              <button type="button" className="btn" onClick={older} disabled={state.busy}>
                Older events
              </button>
            ) : null}
          </>
        )
      ) : null}
    </section>
  );
}
