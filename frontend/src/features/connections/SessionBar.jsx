import { useEffect, useImperativeHandle, useRef, useState } from 'react';
import { closeSession, describeConnectionError, heartbeat, openSession } from '../../api/dbsessions.js';
import { ConfirmDialog, ErrorPanel, Spinner, StatusBadge } from '../../components/common/index.jsx';
import { DatabaseList } from './ConnectPanel.jsx';

const HEARTBEAT_MS = 30_000;

/**
 * Shown when the database session has ended (closed by the server, lost, expired): reconnect to the same database
 * in one step, or go back to the database list. The new session is opened by the server like any other.
 */
export function LostSession({ profile, database, message, onReconnected, onChooseAnother }) {
  const [state, setState] = useState({ phase: 'idle', error: null });
  const reconnect = async () => {
    setState({ phase: 'reconnecting', error: null });
    try {
      onReconnected(await openSession(profile, database));
    } catch (error) {
      setState({ phase: 'idle', error });
    }
  };
  return (
    <div className="session-bar session-lost" role="alert">
      <StatusBadge tone="danger">Disconnected</StatusBadge>
      <span>{message}</span>
      {state.phase === 'reconnecting' ? (
        <Spinner label={`Reconnecting to ${database}`} />
      ) : (
        <button type="button" className="btn btn-primary" onClick={reconnect}>
          Reconnect to {database}
        </button>
      )}
      <button type="button" className="btn" onClick={onChooseAnother}>
        Choose another database
      </button>
      {state.error ? <span className="session-error">{describeConnectionError(state.error)}</span> : null}
    </div>
  );
}

/**
 * The connected session's status (FN-03): database, role, server version, backend PID, companion state.
 * Sends a heartbeat every 30 s while shown (the server ends abandoned transactions without them) and calls `onLost`
 * when the server reports the session ended. `onHeartbeat` receives each heartbeat answer.
 *
 * Switching database (admin Phase 1): the list comes from the workspace's discovery (`discovery`). The server opens
 * the new session first and closes this one only when that worked (`onSwitched` receives the new session), so a
 * failed switch leaves this session as it was. With an open transaction the user is asked first; the server refuses
 * too (TRANSACTION_OPEN) unless that was confirmed, because closing the session rolls the transaction back. While a
 * statement runs (`busy`) switching is not offered. With `transactionOpen`, Disconnect asks first as well.
 * `ref` exposes {switchTo(database)}, which starts the same switch (the object explorer's Switch).
 */
export default function SessionBar({
  session, profile, discovery, onClosed, onLost, onSwitched, onSwitching, onHeartbeat,
  transactionOpen = false, busy = false, heartbeatMs = HEARTBEAT_MS, ref = null,
}) {
  const [closing, setClosing] = useState(false);
  const [asking, setAsking] = useState(false);
  const [picker, setPicker] = useState(false);
  const [switching, setSwitching] = useState(null);
  const [switchError, setSwitchError] = useState(null);
  const [confirmSwitch, setConfirmSwitch] = useState(null);
  const refs = useRef({ onClosed, onLost, onHeartbeat });
  useEffect(() => {
    refs.current = { onClosed, onLost, onHeartbeat };
  });

  useEffect(() => {
    const timer = setInterval(async () => {
      try {
        const hb = await heartbeat(session.id);
        refs.current.onHeartbeat?.(hb);
      } catch (e) {
        if (e.status === 404 || e.status === 410) {
          clearInterval(timer);
          refs.current.onLost?.(e.status === 410 ? e.message : 'The database session has ended.');
        }
      }
    }, heartbeatMs);
    return () => clearInterval(timer);
  }, [session.id, heartbeatMs]);

  const disconnect = async () => {
    setAsking(false);
    setClosing(true);
    try {
      await closeSession(session.id);
    } catch {
      // already gone on the server: nothing else to do
    }
    refs.current.onClosed?.();
  };

  const openPicker = () => {
    setSwitchError(null);
    setPicker(true);
    discovery?.refresh();
  };

  const doSwitch = async (database, discardTransaction) => {
    setConfirmSwitch(null);
    setSwitchError(null);
    setSwitching(database);
    onSwitching?.(database);
    try {
      const next = await openSession(profile, database, { replaces: session.id, discardTransaction });
      setPicker(false);
      onSwitched(next);
    } catch (error) {
      if (error.code === 'TRANSACTION_OPEN') {
        setConfirmSwitch(database); // the server saw a transaction the page did not know about: ask
      } else if (error.status === 404 || error.status === 410) {
        refs.current.onLost?.('The database session had already ended.');
      } else {
        setSwitchError({ database, error });
      }
    } finally {
      setSwitching(null);
      onSwitching?.(null);
    }
  };
  const choose = (database) => (transactionOpen ? setConfirmSwitch(database) : doSwitch(database, false));
  useImperativeHandle(ref, () => ({
    switchTo: (database) => {
      if (busy || closing || switching || database === session.database) return;
      setSwitchError(null);
      setPicker(true);
      choose(database);
    },
  }));

  return (
    <div className="session-bar-wrap">
      <div className="session-bar" aria-label="Database session">
        <StatusBadge tone="ok">Connected</StatusBadge>
        <span className="session-db" title="Current database">
          {session.database}
        </span>
        <span className="session-meta">
          as <strong>{session.role}</strong> · PostgreSQL {session.serverVersion} · PID {session.backendPid}
        </span>
        {session.companion ? null : (
          <StatusBadge tone="warn" title="Object browsing and cancel escalation are unavailable for this session">
            Limited
          </StatusBadge>
        )}
        <button
          type="button"
          className="btn"
          onClick={picker ? () => setPicker(false) : openPicker}
          disabled={busy || closing || Boolean(switching)}
          aria-expanded={picker}
          title={busy ? 'A statement is running: wait for it or cancel it first' : undefined}
        >
          Switch database
        </button>
        <button type="button" className="btn" onClick={transactionOpen ? () => setAsking(true) : disconnect} disabled={closing || Boolean(switching)}>
          {closing ? 'Disconnecting…' : 'Disconnect'}
        </button>
      </div>
      {picker ? (
        <section className="db-switcher" aria-label="Switch database">
          {switching ? <Spinner label={`Switching to ${switching}`} /> : null}
          {switchError ? (
            <div className="error-panel" role="alert">
              <p className="error-title">Could not switch to {switchError.database}</p>
              <p className="error-message">{describeConnectionError(switchError.error)}</p>
              <p className="muted">
                You are still connected to <strong>{session.database}</strong>.
                {switchError.error.code === 'SESSION_LIMIT' ? ' Disconnect this session (or another one) first, then open the other database.' : ''}
              </p>
            </div>
          ) : null}
          {discovery?.status === 'failed' ? (
            <ErrorPanel title="Could not list your databases" error={{ ...discovery.error, message: describeConnectionError(discovery.error) }} onRetry={discovery.refresh} />
          ) : null}
          {discovery?.status === 'checking' ? <Spinner label="Finding your databases" /> : null}
          {discovery && discovery.databases.length > 0 ? (
            <DatabaseList databases={discovery.databases} current={session.database} onChoose={switching ? () => {} : choose} label="Databases to switch to" />
          ) : null}
          <div className="db-switcher-actions">
            <button type="button" className="btn btn-link" onClick={discovery?.refresh} disabled={discovery?.refreshing}>
              Refresh list
            </button>
            <button type="button" className="btn" onClick={() => setPicker(false)}>
              Close
            </button>
          </div>
        </section>
      ) : null}
      <ConfirmDialog open={asking} title="Disconnect with an open transaction?" confirmLabel="Disconnect and roll back" danger onConfirm={disconnect} onCancel={() => setAsking(false)}>
        Disconnecting rolls back everything in the open transaction that has not been committed.
      </ConfirmDialog>
      <ConfirmDialog
        open={Boolean(confirmSwitch)}
        title="Switch with an open transaction?"
        confirmLabel="Roll back and switch"
        danger
        onConfirm={() => doSwitch(confirmSwitch, true)}
        onCancel={() => setConfirmSwitch(null)}
      >
        Switching to {confirmSwitch} closes this session. PostgreSQL rolls back everything in the open transaction that has not been
        committed; nothing is committed for you. To keep the changes, cancel and commit first.
      </ConfirmDialog>
    </div>
  );
}
