import { useCallback, useEffect, useRef, useState } from 'react';
import { activity, cancel, execute, transaction } from '../../api/execution.js';

const IDLE = { running: false, result: null, error: null, elapsedMs: 0, lockWait: false, waitEvent: null, cancelling: false, lastText: null, lastFrom: 0, confirm: null };

/**
 * Execution state for one DB session: run (one at a time; the server enforces it too), cancel, an elapsed timer,
 * a once-a-second activity poll while running (lock waits), transaction controls (plan Phase 9) and the
 * confirmation step for destructive statements. Nothing about results is stored in the browser beyond memory.
 *
 * Transaction state comes from the server: the last result's `transaction` or the heartbeat. In manual mode a
 * statement run outside a transaction first begins one with the chosen isolation level and read-only option.
 *
 * `onSessionEnded` is called when the server reports the session closed (quarantine, loss).
 */
export default function useExecution(sessionId, { onSessionEnded, pollMs = 1000, tickMs = 100 } = {}) {
  const [state, setState] = useState(IDLE);
  const [tx, setTx] = useState({ state: 'IDLE', mode: 'auto', isolation: '', readOnly: false, lifetime: null, begunWith: null });
  const started = useRef(0);
  const busy = useRef(false);
  const endedRef = useRef(onSessionEnded);
  const txRef = useRef(tx);
  useEffect(() => {
    endedRef.current = onSessionEnded;
    txRef.current = tx;
  });

  useEffect(() => {
    setState(IDLE);
    setTx((t) => ({ ...t, state: 'IDLE', lifetime: null, begunWith: null }));
  }, [sessionId]);

  /** Runs one request (statement or transaction action) with the running state, timer and error handling. */
  const perform = useCallback(
    async (request, { text = null, from = 0 } = {}) => {
      if (!sessionId || busy.current) return null;
      busy.current = true;
      setState({ ...IDLE, running: true, lastText: text, lastFrom: from });
      started.current = Date.now();
      try {
        const result = await request();
        setState((s) => ({ ...s, running: false, cancelling: false, result }));
        setTx((t) => ({ ...t, state: result.transaction || t.state, begunWith: result.transaction === 'IDLE' ? null : t.begunWith }));
        if (result.sessionEnded) endedRef.current?.(result);
        return result;
      } catch (error) {
        const confirm = error.code === 'CONFIRMATION_REQUIRED' ? { text, from, reasons: error.details?.reasons || [] } : null;
        setState((s) => ({ ...s, running: false, cancelling: false, error: confirm ? null : error, confirm }));
        if (error.status === 404 || error.status === 410) endedRef.current?.(error);
        return null;
      } finally {
        busy.current = false;
      }
    },
    [sessionId],
  );

  const begin = useCallback(async () => {
    const { isolation, readOnly } = txRef.current;
    const result = await perform(() => transaction(sessionId, 'begin', { isolation, readOnly }));
    if (result?.transaction === 'OPEN') setTx((t) => ({ ...t, begunWith: { isolation: isolation || 'server default', readOnly } }));
    return result;
  }, [perform, sessionId]);

  const run = useCallback(
    async (text, from = 0, { confirmed = false } = {}) => {
      if (txRef.current.mode === 'manual' && txRef.current.state === 'IDLE') {
        const begun = await begin();
        if (begun?.transaction !== 'OPEN') return;
      }
      await perform(() => execute(sessionId, text, confirmed), { text, from });
    },
    [perform, begin, sessionId],
  );

  const confirmRun = useCallback(() => {
    const c = state.confirm;
    if (c) run(c.text, c.from, { confirmed: true });
  }, [run, state.confirm]);

  const dismissConfirm = useCallback(() => setState((s) => ({ ...s, confirm: null })), []);

  const commit = useCallback(() => perform(() => transaction(sessionId, 'commit')), [perform, sessionId]);
  const rollback = useCallback(() => perform(() => transaction(sessionId, 'rollback')), [perform, sessionId]);

  const stop = useCallback(async () => {
    setState((s) => (s.running ? { ...s, cancelling: true } : s));
    try {
      await cancel(sessionId);
    } catch {
      // NOT_RUNNING: it finished first; the execute response says how it ended
    }
  }, [sessionId]);

  const refresh = useCallback(() => {
    if (state.lastText) run(state.lastText, state.lastFrom);
  }, [run, state.lastText, state.lastFrom]);

  /** The heartbeat reports the transaction state and when the reaper would close the session. */
  const observeHeartbeat = useCallback((hb) => {
    setTx((t) => ({
      ...t,
      state: hb.transaction || t.state,
      lifetime: hb.closesAt ? { closesAt: hb.closesAt, closeReason: hb.closeReason, warning: Boolean(hb.warning) } : null,
    }));
  }, []);

  const setMode = useCallback((mode) => setTx((t) => ({ ...t, mode })), []);
  const setIsolation = useCallback((isolation) => setTx((t) => ({ ...t, isolation })), []);
  const setReadOnly = useCallback((readOnly) => setTx((t) => ({ ...t, readOnly })), []);

  const { running } = state;
  useEffect(() => {
    if (!running) return undefined;
    const tick = setInterval(() => setState((s) => ({ ...s, elapsedMs: Date.now() - started.current })), tickMs);
    const poll = setInterval(async () => {
      try {
        const a = await activity(sessionId);
        setState((s) => (s.running ? { ...s, lockWait: Boolean(a.lockWait), waitEvent: a.waitEvent || null } : s));
      } catch {
        // the execute request reports problems; the poll is only a hint
      }
    }, pollMs);
    return () => {
      clearInterval(tick);
      clearInterval(poll);
    };
  }, [running, sessionId, pollMs, tickMs]);

  return {
    ...state,
    tx,
    run,
    cancel: stop,
    refresh,
    confirmRun,
    dismissConfirm,
    begin,
    commit,
    rollback,
    observeHeartbeat,
    setMode,
    setIsolation,
    setReadOnly,
  };
}
