import { useEffect, useMemo, useState } from 'react';
import { describeMetadataError, getActivity, getDatabaseInfo, getRoles, getSizes } from '../../api/metadata.js';
import { Spinner, StatusBadge } from '../../components/common/index.jsx';
import { formatBytes } from '../editor/sqlFiles.js';

function time(iso) {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleTimeString('en-GB');
}

function dateTime(iso) {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('en-GB');
}

/** "4 s", "3 min", "2 h" between two instants; empty when either is missing. */
export function since(fromIso, toIso) {
  if (!fromIso || !toIso) return '';
  const s = Math.max(0, Math.round((new Date(toIso) - new Date(fromIso)) / 1000));
  if (s < 120) return `${s} s`;
  if (s < 7200) return `${Math.round(s / 60)} min`;
  return `${Math.round(s / 3600)} h`;
}

/** Loads once when shown and again on Refresh; nothing is polled. */
function useOnDemand(load, key) {
  const [state, setState] = useState({ key: null, data: null, error: null });
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let live = true;
    load().then(
      (data) => live && setState({ key: `${key}#${attempt}`, data, error: null }),
      (error) => live && setState({ key: `${key}#${attempt}`, data: null, error }),
    );
    return () => {
      live = false;
    };
    // `load` is recreated every render; `key` and `attempt` say when to load again
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, attempt]);
  const current = state.key === `${key}#${attempt}`;
  return { data: current ? state.data : null, error: current ? state.error : null, loading: !current, refresh: () => setAttempt((a) => a + 1) };
}

function Toolbar({ loadedAt, onRefresh, loading, children }) {
  return (
    <div className="admin-toolbar">
      <span className="muted">{loadedAt ? `Loaded at ${time(loadedAt)}` : ' '}</span>
      {children}
      <button type="button" className="btn" onClick={onRefresh} disabled={loading}>
        Refresh
      </button>
    </div>
  );
}

function Problem({ error, onRetry }) {
  return (
    <div className="result-error" role="alert">
      {describeMetadataError(error)}{' '}
      <button type="button" className="btn btn-link" onClick={onRetry}>
        Try again
      </button>
    </div>
  );
}

function Truncated({ show, what }) {
  return show ? (
    <p className="result-truncated" role="note">
      Only the first {what} are shown.
    </p>
  ) : null;
}

/**
 * Server activity (admin Phase 3): client sessions with their state, what they wait for and who blocks them, and the
 * locks of other sessions. Read once when opened and on Refresh, never polled. What other sessions show is decided
 * by PostgreSQL: details of your own sessions, everyone's only with pg_read_all_stats.
 */
export function ActivityPanel({ sessionId }) {
  const { data, error, loading, refresh } = useOnDemand(() => getActivity(sessionId), sessionId);
  const [hideIdle, setHideIdle] = useState(false);
  const rows = useMemo(() => (data ? data.activity.sessions.filter((s) => !hideIdle || s.state !== 'idle') : []), [data, hideIdle]);
  return (
    <section className="admin-panel" aria-label="Server activity">
      <Toolbar loadedAt={data?.loadedAt} onRefresh={refresh} loading={loading}>
        <label className="tree-option">
          <input type="checkbox" checked={hideIdle} onChange={(e) => setHideIdle(e.target.checked)} /> Hide idle sessions
        </label>
      </Toolbar>
      {loading ? <Spinner label="Loading activity" /> : null}
      {error ? <Problem error={error} onRetry={refresh} /> : null}
      {data ? (
        <>
          <table className="grid admin-table">
            <caption>Sessions ({rows.length})</caption>
            <thead>
              <tr>
                <th scope="col">PID</th>
                <th scope="col">User</th>
                <th scope="col">Database</th>
                <th scope="col">Application</th>
                <th scope="col">State</th>
                <th scope="col">Waiting for</th>
                <th scope="col">Running for</th>
                <th scope="col">Query</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((s) => (
                <tr key={s.pid}>
                  <td>
                    {s.pid}
                    {s.mine === 'session' ? <StatusBadge tone="ok">this session</StatusBadge> : null}
                    {s.mine === 'companion' ? <StatusBadge tone="neutral" title="The connection pgweb uses to read the catalog for this session">browser</StatusBadge> : null}
                  </td>
                  <td>{s.user}</td>
                  <td>{s.database}</td>
                  <td>{s.application}</td>
                  <td>{s.visible ? s.state : <span className="muted">not visible to your role</span>}</td>
                  <td>
                    {s.waitEventType ? `${s.waitEventType}: ${s.waitEvent}` : ''}
                    {s.blockedBy ? <StatusBadge tone="danger">blocked by {s.blockedBy.join(', ')}</StatusBadge> : null}
                  </td>
                  <td>{s.state === 'active' ? since(s.queryStart, data.loadedAt) : ''}</td>
                  <td className="admin-query" title={s.query}>
                    {s.query}
                    {s.queryTruncated ? <span className="muted"> …</span> : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <Truncated show={data.activity.sessionsTruncated} what="500 sessions" />
          <table className="grid admin-table">
            <caption>Locks of other sessions ({data.activity.locks.length})</caption>
            <thead>
              <tr>
                <th scope="col">PID</th>
                <th scope="col">Lock</th>
                <th scope="col">On</th>
                <th scope="col">Mode</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {data.activity.locks.map((l, i) => (
                <tr key={`${l.pid}:${i}`}>
                  <td>{l.pid}</td>
                  <td>{l.type}</td>
                  <td>{l.relation || (l.transactionId ? `transaction ${l.transactionId}` : l.database || '')}</td>
                  <td>{l.mode}</td>
                  <td>{l.granted ? 'held' : <StatusBadge tone="warn">waiting{l.waitStart ? ` since ${time(l.waitStart)}` : ''}</StatusBadge>}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <Truncated show={data.activity.locksTruncated} what="1,000 locks" />
          <p className="muted admin-note">
            Read once when opened; Refresh reads again. What you see of other users&apos; sessions depends on your PostgreSQL privileges.
            Nothing here can cancel or end a session.
          </p>
        </>
      ) : null}
    </section>
  );
}

function size(bytes) {
  return typeof bytes === 'number' ? formatBytes(bytes) : '—';
}

function DatabaseProperties({ db }) {
  const can = [db.canCreateSchemas ? 'create schemas' : null, db.canCreateTemp ? 'create temporary tables' : null].filter(Boolean);
  const items = [
    ['Name', db.name],
    ['Owner', db.owner],
    ['Your role', db.currentRole],
    ['You can', can.length ? can.join(', ') : 'neither create schemas nor temporary tables'],
    ['Size', size(db.sizeBytes)],
    ['Encoding', db.encoding],
    ['Collation', db.collate],
    ['Character type', db.ctype],
    ['Tablespace', db.tablespace],
    ['Connection limit', db.connectionLimit < 0 ? 'No limit' : String(db.connectionLimit)],
    ['Server version', db.serverVersion],
    ['Server started', dateTime(db.serverStarted)],
  ];
  if (db.comment) items.push(['Comment', db.comment]);
  return (
    <dl className="admin-properties">
      {items.map(([k, v]) => (
        <div key={k}>
          <dt>{k}</dt>
          <dd>{v}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Grants as "grantee: privilege, privilege*" lines (* = with grant option). */
export function GrantList({ grants, label }) {
  const byGrantee = new Map();
  for (const g of grants) {
    const list = byGrantee.get(g.grantee) || [];
    list.push(g.grantable ? `${g.privilege} (with grant option)` : g.privilege);
    byGrantee.set(g.grantee, list);
  }
  return (
    <ul className="tree-list" aria-label={label}>
      {[...byGrantee.entries()].map(([grantee, privileges]) => (
        <li key={grantee} className="tree-leaf">
          <span className="tree-name">{grantee}</span> <span className="muted">{privileges.join(', ')}</span>
        </li>
      ))}
    </ul>
  );
}

/**
 * The connected database (admin Phase 3): its properties, what your role may do in it and who was granted what, then
 * the sizes of the databases you may connect to and of the largest tables you can use here.
 */
export function DatabasePanel({ sessionId }) {
  const info = useOnDemand(() => getDatabaseInfo(sessionId), sessionId);
  const sizes = useOnDemand(() => getSizes(sessionId), sessionId);
  const refresh = () => {
    info.refresh();
    sizes.refresh();
  };
  const db = info.data?.database;
  return (
    <section className="admin-panel" aria-label="Database">
      <Toolbar loadedAt={info.data?.loadedAt} onRefresh={refresh} loading={info.loading || sizes.loading} />
      {info.loading ? <Spinner label="Loading database properties" /> : null}
      {info.error ? <Problem error={info.error} onRetry={info.refresh} /> : null}
      {db ? (
        <>
          <h3 className="admin-heading">Properties</h3>
          <DatabaseProperties db={db} />
          <h3 className="admin-heading">Privileges on this database</h3>
          <GrantList grants={db.grants} label="Privileges on this database" />
        </>
      ) : null}
      {sizes.loading ? <Spinner label="Loading sizes" /> : null}
      {sizes.error ? <Problem error={sizes.error} onRetry={sizes.refresh} /> : null}
      {sizes.data ? (
        <>
          <table className="grid admin-table">
            <caption>Databases</caption>
            <thead>
              <tr>
                <th scope="col">Database</th>
                <th scope="col">Size</th>
              </tr>
            </thead>
            <tbody>
              {sizes.data.sizes.databases.map((d) => (
                <tr key={d.name}>
                  <td>{d.name}</td>
                  <td title={typeof d.sizeBytes === 'number' ? undefined : 'You may not connect to this database'}>{size(d.sizeBytes)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <Truncated show={sizes.data.sizes.databasesTruncated} what="500 databases" />
          <table className="grid admin-table">
            <caption>Largest tables you can use (up to {sizes.data.sizes.tablesLimit})</caption>
            <thead>
              <tr>
                <th scope="col">Table</th>
                <th scope="col">Total</th>
                <th scope="col">Data</th>
                <th scope="col">Indexes</th>
                <th scope="col">Estimated rows</th>
              </tr>
            </thead>
            <tbody>
              {sizes.data.sizes.tables.map((t) => (
                <tr key={`${t.schema}.${t.name}`}>
                  <td>
                    {t.schema}.{t.name}
                    {t.kind === 'table' ? null : <span className="muted"> ({t.kind.replace('_', ' ')})</span>}
                  </td>
                  <td>{size(t.totalBytes)}</td>
                  <td>{size(t.tableBytes)}</td>
                  <td>{size(t.indexBytes)}</td>
                  <td>{typeof t.estimatedRows === 'number' ? t.estimatedRows.toLocaleString('en-GB') : 'not analysed'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      ) : null}
    </section>
  );
}

const ATTRIBUTE_LABELS = {
  superuser: 'superuser',
  noinherit: 'no inherit',
  createrole: 'create role',
  createdb: 'create database',
  login: 'can log in',
  replication: 'replication',
  bypassrls: 'bypass RLS',
};

function membershipLabel(m, name) {
  const options = [m.admin ? 'admin' : null, m.inherit ? null : 'no inherit', m.set ? null : 'no set'].filter(Boolean);
  return options.length ? `${name} (${options.join(', ')})` : name;
}

/**
 * Roles and memberships (admin Phase 3), read-only: attributes, who is a member of what and with which options,
 * and whether you are a member. No passwords exist or are shown (Kerberos only, ADR-0002).
 */
export function RolesPanel({ sessionId }) {
  const [predefined, setPredefined] = useState(false);
  const [filter, setFilter] = useState('');
  const { data, error, loading, refresh } = useOnDemand(() => getRoles(sessionId, predefined), `${sessionId}:${predefined}`);
  const view = useMemo(() => {
    if (!data) return [];
    const f = filter.trim().toLowerCase();
    const members = data.roles.memberships;
    return data.roles.roles
      .filter((r) => !f || r.name.toLowerCase().includes(f))
      .map((r) => ({
        ...r,
        memberOf: members.filter((m) => m.member === r.name).map((m) => membershipLabel(m, m.role)),
        members: members.filter((m) => m.role === r.name).map((m) => membershipLabel(m, m.member)),
      }));
  }, [data, filter]);
  return (
    <section className="admin-panel" aria-label="Roles">
      <Toolbar loadedAt={data?.loadedAt} onRefresh={refresh} loading={loading}>
        <input type="search" className="admin-filter" aria-label="Filter roles" placeholder="Filter roles" value={filter} onChange={(e) => setFilter(e.target.value)} />
        <label className="tree-option">
          <input type="checkbox" checked={predefined} onChange={(e) => setPredefined(e.target.checked)} /> Show predefined roles (pg_*)
        </label>
      </Toolbar>
      {loading ? <Spinner label="Loading roles" /> : null}
      {error ? <Problem error={error} onRetry={refresh} /> : null}
      {data ? (
        <>
          <table className="grid admin-table">
            <caption>Roles ({view.length})</caption>
            <thead>
              <tr>
                <th scope="col">Role</th>
                <th scope="col">Attributes</th>
                <th scope="col">Member of</th>
                <th scope="col">Members</th>
                <th scope="col">You</th>
              </tr>
            </thead>
            <tbody>
              {view.map((r) => (
                <tr key={r.name}>
                  <td title={r.comment}>{r.name}</td>
                  <td>
                    {r.attributes.map((a) => ATTRIBUTE_LABELS[a] || a).join(', ')}
                    {typeof r.connectionLimit === 'number' ? `${r.attributes.length ? ', ' : ''}${r.connectionLimit} connections` : ''}
                  </td>
                  <td>{r.memberOf.join(', ')}</td>
                  <td>{r.members.join(', ')}</td>
                  <td>{r.current ? <StatusBadge tone="ok">you</StatusBadge> : r.member ? 'member' : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <Truncated show={data.roles.truncated} what="2,000 roles" />
          <Truncated show={data.roles.membershipsTruncated} what="5,000 memberships" />
          <p className="muted admin-note">Read-only. Roles and privileges are changed with SQL (GRANT, REVOKE, ALTER ROLE), run under your own role.</p>
        </>
      ) : null}
    </section>
  );
}
