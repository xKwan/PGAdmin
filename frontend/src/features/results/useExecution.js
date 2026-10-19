import { useCallback, useEffect, useRef, useState } from 'react';
import { activity, cancel, execute, fetchMore, runScript, transaction } from '../../api/execution.js';

const IDLE = {
  running: false, result: null, error: null, elapsedMs: 0, lockWait: false, waitEvent: null, cancelling: false,
  lastText: null, lastFrom: 0, lastScript: false, confirm: null, progress: null, fetching: false, fetchError: null,
};

/** Appends a further page (P9-3) to the first result: rows, cut-cell positions, and whether there is still more. */
export function appendPage(result, page) {
  const [current, ...rest] = result.results;
  const next = page.results?.[0];
  if (!current || !next) return result;
  const offset = current.rows.length;
  const merged = {
    ...current,
    rows: [...current.rows, ...next.rows],
    rowCount: current.rows.length + next.rows.length,
    truncated: Boolean(next.truncated),
    more: Boolean(next.more),
    truncatedCells: [...(current.truncatedCells || []), ...(next.truncatedCells || []).map(([r, c]) => [r + offset, c])],
  };
  if (next.truncatedBy) merged.truncatedBy = next.truncatedBy;
  else delete merged.truncatedBy;
  return { ...result, results: [merged, ...rest], transaction: page.transaction || result.transaction, retrievedAt: page.retrievedAt || result.retrievedAt };
}

/**
 * Execution state for one DB session: run one statement or a script (one at a time; the server enforces it too),
 * cancel, an elapsed timer, a once-a-second activity poll while running (lock waits, script progress), transaction
 * controls (plan Phase 9), the confirmation step for destructive statements, and further pages of a result (P9-3).
 * Nothing about results is stored in the browser beyond memory.
 *
 * Transaction state comes from the server: the last result's `transaction` or the heartbeat. In manual mode a
 * statement or script run outside a transaction first begins one with the chosen isolation level and read-only option.
 *
 * `onSessionEnded` is called when the server reports the session closed (quarantine, loss). `onFinished` receives
 * {text, script, result, error} after every statement or script (for the query history); `onResult` receives every
 * result document, transaction actions included.
 */
export default function useExecution(sessionId, { onSessionEnded, onFinished, onResult, pollMs = 1000, tickMs = 100 } = {}) {
  const [state, setState] = useState(IDLE);
  const [tx, setTx] = useState({ state: 'IDLE', mode: 'auto', isolation: '', readOnly: false, lifetime: null, begunWith: null });
  const started = useRef(0);
  const busy = useRef(false);
  const endedRef = useRef(onSessionEnded);
  const finishedRef = useRef(onFinished);
  const resultRef = useRef(onResult);
  const txRef = useRef(tx);
  useEffect(() => {
    endedRef.current = onSessionEnded;
    finishedRef.current = onFinished;
    resultRef.current = onResult;
    txRef.current = tx;
  });

  useEffect(() => {
    setState(IDLE);
    setTx((t) => ({ ...t, state: 'IDLE', lifetime: null, begunWith: null }));
  }, [sessionId]);

  /** Runs one request (statement, script or transaction action) with the running state, timer and error handling. */
  const perform = useCallback(
    async (request, { text = null, from = 0, script = false, record = false } = {}) => {
      if (!sessionId || busy.current) return null;
      busy.current = true;
      setState({ ...IDLE, running: true, lastText: text, lastFrom: from, lastScript: script });
      started.current = Date.now();
      try {
        const result = await request();
        setState((s) => ({ ...s, running: false, cancelling: false, progress: null, result }));
        setTx((t) => ({ ...t, state: result.transaction || t.state, begunWith: result.transaction === 'IDLE' ? null : t.begunWith }));
        if (result.sessionEnded) endedRef.current?.(result);
        resultRef.current?.(result);
        if (record) finishedRef.current?.({ text, script, result, error: null });
        return result;
      } catch (error) {
        const confirm = error.code === 'CONFIRMATION_REQUIRED' ? { text, from, script, reasons: error.details?.reasons || [] } : null;
        setState((s) => ({ ...s, running: false, cancelling: false, progress: null, error: confirm ? null : error, confirm }));
        if (error.status === 404 || error.status === 410) endedRef.current?.(error);
        if (record && !confirm) finishedRef.current?.({ text, script, result: null, error });
        return null;
      } finally {
        busy.current = false;
      }
    },
    [sessionId],
  );

  const begin = useCallback(async (options = {}) => {
    const isolation = options.isolation ?? txRef.current.isolation;
    const readOnly = options.readOnly ?? txRef.current.readOnly;
    const result = await perform(() => transaction(sessionId, 'begin', { isolation, readOnly }));
    if (result?.transaction === 'OPEN') setTx((t) => ({ ...t, begunWith: { isolation: isolation || 'server default', readOnly } }));
    return result;
  }, [perform, sessionId]);

  /**
   * Sends `go` at once, or in manual mode outside a transaction after beginning one (nothing is sent if that
   * failed). Without a BEGIN the request starts synchronously, so the page shows it running straight away.
   */
  const afterBeginIfManual = useCallback(
    (go) => {
      if (txRef.current.mode === 'manual' && txRef.current.state === 'IDLE') {
        return begin().then((begun) => (begun?.transaction === 'OPEN' ? go() : null));
      }
      return go();
    },
    [begin],
  );

  const run = useCallback(
    (text, from = 0, { confirmed = false } = {}) =>
      afterBeginIfManual(() => perform(() => execute(sessionId, text, confirmed), { text, from, record: true })),
    [perform, afterBeginIfManual, sessionId],
  );

  /** Run script (EXE-03): the whole text, statement by statement, stopping at the first failure. */
  const runAll = useCallback(
    (text, from = 0, { confirmed = false } = {}) =>
      afterBeginIfManual(() => perform(() => runScript(sessionId, text, confirmed), { text, from, script: true, record: true })),
    [perform, afterBeginIfManual, sessionId],
  );

  /**
   * Outside a transaction a result can't continue on the server, so it was cut at one page. This begins a read-only
   * transaction (it can't change anything) and runs the statement again, so the rest can be loaded page by page.
   */
  const runInReadOnlyTransaction = useCallback(
    async (text, from = 0) => {
      const begun = await begin({ readOnly: true });
      if (begun?.transaction !== 'OPEN') return;
      await perform(() => execute(sessionId, text, false), { text, from, record: true });
    },
    [begin, perform, sessionId],
  );

  /** Loads the next page of the current result into it. */
  const loadMore = useCallback(async () => {
    const result = state.result;
    if (!sessionId || busy.current || !result?.results?.[0]?.more) return;
    busy.current = true;
    setState((s) => ({ ...s, fetching: true, fetchError: null }));
    try {
      const page = await fetchMore(sessionId, result.executionId);
      setState((s) => ({ ...s, fetching: false, result: s.result === result ? appendPage(result, page) : s.result }));
      setTx((t) => ({ ...t, state: page.transaction || t.state }));
      if (page.sessionEnded) endedRef.current?.(page);
    } catch (error) {
      setState((s) => {
        if (s.result !== result) return { ...s, fetching: false };
        // The cursor is gone (or the page failed): nothing more can be loaded for this result.
        const [first, ...rest] = result.results;
        return { ...s, fetching: false, fetchError: error, result: { ...result, results: [{ ...first, more: false }, ...rest] } };
      });
      if (error.status === 404 || error.status === 410) endedRef.current?.(error);
    } finally {
      busy.current = false;
    }
  }, [sessionId, state.result]);

  const confirmRun = useCallback(() => {
    const c = state.confirm;
    if (!c) return;
    if (c.script) runAll(c.text, c.from, { confirmed: true });
    else run(c.text, c.from, { confirmed: true });
  }, [run, runAll, state.confirm]);

  const dismissConfirm = useCallback(() => setState((s) => ({ ...s, confirm: null })), []);

  const commit = useCallback(() => perform(() => transaction(sessionId, 'commit')), [perform, sessionId]);
  const rollback = useCallback(() => perform(() => transaction(sessionId, 'rollback')), [perform, sessionId]);

  const stop = useCallback(async () => {
    setState((s) => (s.running || s.fetching ? { ...s, cancelling: true } : s));
    try {
      await cancel(sessionId);
    } catch {
      // NOT_RUNNING: it finished first; the execute response says how it ended
    }
  }, [sessionId]);

  const refresh = useCallback(() => {
    if (!state.lastText) return;
    if (state.lastScript) runAll(state.lastText, state.lastFrom);
    else run(state.lastText, state.lastFrom);
  }, [run, runAll, state.lastText, state.lastFrom, state.lastScript]);

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
        const progress = a.scriptStatements ? { statement: a.scriptStatement, of: a.scriptStatements } : null;
        setState((s) => (s.running ? { ...s, lockWait: Boolean(a.lockWait), waitEvent: a.waitEvent || null, progress } : s));
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
    runScript: runAll,
    runInReadOnlyTransaction,
    loadMore,
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
