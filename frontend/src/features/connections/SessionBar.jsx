import { useEffect, useRef, useState } from 'react';
import { closeSession, heartbeat } from '../../api/dbsessions.js';
import { ConfirmDialog, StatusBadge } from '../../components/common/index.jsx';

const HEARTBEAT_MS = 30_000;

/**
 * The connected session's status (FN-03): database, role, server version, backend PID, companion state.
 * Sends a heartbeat every 30 s while shown (the server ends abandoned transactions without them) and reports a lost
 * or expired session. `onHeartbeat` receives each heartbeat answer (transaction state, when the server would close
 * the session). With `transactionOpen`, Disconnect asks first: closing rolls the transaction back.
 */
export default function SessionBar({ session, onClosed, onHeartbeat, transactionOpen = false, heartbeatMs = HEARTBEAT_MS }) {
  const [lost, setLost] = useState(null);
  const [closing, setClosing] = useState(false);
  const [asking, setAsking] = useState(false);
  const onClosedRef = useRef(onClosed);
  const onHeartbeatRef = useRef(onHeartbeat);
  useEffect(() => {
    onClosedRef.current = onClosed;
    onHeartbeatRef.current = onHeartbeat;
  });

  useEffect(() => {
    const timer = setInterval(async () => {
      try {
        const hb = await heartbeat(session.id);
        onHeartbeatRef.current?.(hb);
      } catch (e) {
        if (e.status === 404 || e.status === 410) {
          setLost(e.status === 410 ? e.message : 'The database session has ended.');
          clearInterval(timer);
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
    onClosedRef.current?.();
  };

  if (lost) {
    return (
      <div className="session-bar session-lost" role="alert">
        <StatusBadge tone="danger">Disconnected</StatusBadge>
        <span>{lost}</span>
        <button type="button" className="btn" onClick={() => onClosedRef.current?.()}>
          Connect again
        </button>
      </div>
    );
  }
  return (
    <div className="session-bar" aria-label="Database session">
      <StatusBadge tone="ok">Connected</StatusBadge>
      <span className="session-db">{session.database}</span>
      <span className="session-meta">
        as <strong>{session.role}</strong> · PostgreSQL {session.serverVersion} · PID {session.backendPid}
      </span>
      {session.companion ? null : (
        <StatusBadge tone="warn" title="Object browsing and cancel escalation are unavailable for this session">
          Limited
        </StatusBadge>
      )}
      <button type="button" className="btn" onClick={transactionOpen ? () => setAsking(true) : disconnect} disabled={closing}>
        {closing ? 'Disconnecting…' : 'Disconnect'}
      </button>
      <ConfirmDialog open={asking} title="Disconnect with an open transaction?" confirmLabel="Disconnect and roll back" danger onConfirm={disconnect} onCancel={() => setAsking(false)}>
        Disconnecting rolls back everything in the open transaction that has not been committed.
      </ConfirmDialog>
    </div>
  );
}
