import { useCallback, useEffect, useRef, useState } from 'react';
import { activity, cancel, execute } from '../../api/execution.js';

const IDLE = { running: false, result: null, error: null, elapsedMs: 0, lockWait: false, waitEvent: null, cancelling: false, lastText: null, lastFrom: 0 };

/**
 * Execution state for one DB session: run (one at a time; the server enforces it too), cancel, an elapsed timer,
 * and a once-a-second activity poll while running (lock waits). Nothing about results is stored in the browser
 * beyond this component's memory.
 *
 * `onSessionEnded` is called when the server reports the session closed (quarantine, loss).
 */
export default function useExecution(sessionId, { onSessionEnded, pollMs = 1000, tickMs = 100 } = {}) {
  const [state, setState] = useState(IDLE);
  const started = useRef(0);
  const busy = useRef(false);
  const endedRef = useRef(onSessionEnded);
  useEffect(() => {
    endedRef.current = onSessionEnded;
  });

  useEffect(() => setState(IDLE), [sessionId]);

  const run = useCallback(
    async (text, from = 0) => {
      if (!sessionId || busy.current) return;
      busy.current = true;
      setState({ ...IDLE, running: true, lastText: text, lastFrom: from });
      started.current = Date.now();
      try {
        const result = await execute(sessionId, text);
        setState((s) => ({ ...s, running: false, cancelling: false, result }));
        if (result.sessionEnded) endedRef.current?.(result);
      } catch (error) {
        setState((s) => ({ ...s, running: false, cancelling: false, error }));
        if (error.status === 404 || error.status === 410) endedRef.current?.(error);
      } finally {
        busy.current = false;
      }
    },
    [sessionId],
  );

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

  return { ...state, run, cancel: stop, refresh };
}
