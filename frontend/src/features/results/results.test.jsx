import { act, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { describeCancelOutcome, describeExecutionError } from '../../api/execution.js';
import { envelope, http, HttpResponse, server } from '../../mocks/server.js';
import { axeViolations } from '../../test/a11y.js';
import { randomText, seededRandom } from '../../test/random.js';
import ResultGrid, { compareCells } from './ResultGrid.jsx';
import ResultView, { commandSummary } from './ResultView.jsx';
import useExecution from './useExecution.js';

const SID = 'AbCdEfGhIjKlMnOpQrStUv';

function doc(over = {}) {
  return {
    executionId: 'e1',
    statementClass: 'select',
    kind: 'rows',
    results: [],
    transaction: 'IDLE',
    durationMs: 12,
    retrievedAt: '2026-10-16T08:00:01Z',
    ...over,
  };
}

function rowsResult(columns, rows, over = {}) {
  return { kind: 'rows', columns, rows, rowCount: rows.length, truncated: false, ...over };
}

const idle = { running: false, result: null, error: null, elapsedMs: 0, lockWait: false, waitEvent: null, cancelling: false, lastText: null };

function view(execution, props = {}) {
  const handlers = { onCancel: vi.fn(), onRefresh: vi.fn(), onShowPosition: vi.fn() };
  const r = render(<ResultView execution={{ ...idle, ...execution }} {...handlers} {...props} />);
  return { ...r, ...handlers };
}

describe('ResultView', () => {
  it('starts empty', () => {
    view({});
    expect(screen.getByText('No results yet')).toBeInTheDocument();
  });

  it('shows NULL, the empty string and the text NULL differently, with column types', async () => {
    const result = doc({
      results: [rowsResult([{ name: 'n', type: 'text' }, { name: 'e', type: 'text' }, { name: 'literal', type: 'text' }], [[null, '', 'NULL']])],
    });
    const { container } = view({ result, lastText: 'SELECT …' });
    const cells = screen.getAllByRole('row')[1].querySelectorAll('td');
    expect(cells[1].querySelector('.null-marker')).not.toBeNull();
    expect(cells[1]).toHaveClass('cell-null');
    expect(cells[2].textContent).toBe('');
    expect(cells[2]).not.toHaveClass('cell-null');
    expect(cells[3].textContent).toBe('NULL');
    expect(cells[3].querySelector('.null-marker')).toBeNull();
    expect(screen.getAllByText('text')).toHaveLength(3);
    expect(screen.getByText(/Retrieved at/)).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('renders markup in values as text', () => {
    const payloads = ['<img src=x onerror="window.__xss=1">', '<script>window.__xss=2</script>', '"><svg onload=alert(1)>'];
    view({ result: doc({ results: [rowsResult([{ name: '<b>col</b>', type: 'text' }], payloads.map((p) => [p]))] }) });
    for (const p of payloads) expect(screen.getByText(p)).toBeInTheDocument();
    expect(screen.getByText('<b>col</b>')).toBeInTheDocument();
    expect(document.querySelector('img, script:not([src]), svg[onload], b')).toBeNull();
    expect(window.__xss).toBeUndefined();
  });

  it('marks truncated results and cut cells', () => {
    view({
      result: doc({
        results: [rowsResult([{ name: 'v', type: 'text' }], [['abc'], ['d']], { truncated: true, truncatedBy: 'rows', truncatedCells: [[0, 0]] })],
      }),
    });
    expect(screen.getByRole('note')).toHaveTextContent('Only the first 2 rows are shown (limit reached).');
    expect(screen.getByTitle('This value was cut at the cell size limit')).toBeInTheDocument();
    view({ result: doc({ results: [rowsResult([{ name: 'v', type: 'text' }], [['x']], { truncated: true, truncatedBy: 'bytes' })] }) });
    expect(screen.getByText(/size limit was reached/)).toBeInTheDocument();
  });

  it('reports commands with their counts', () => {
    const { unmount } = view({ result: doc({ kind: 'command', statementClass: 'dml', results: [{ kind: 'command', rowCount: 7 }] }) });
    expect(screen.getByRole('status')).toHaveTextContent('Completed. 7 rows affected.');
    unmount();
    view({ result: doc({ kind: 'command', statementClass: 'ddl', results: [{ kind: 'command' }] }) });
    expect(screen.getByRole('status')).toHaveTextContent(/^Completed\.$/);
  });

  it('shows a PostgreSQL error with its position and details', async () => {
    const { onShowPosition } = view({
      result: doc({ kind: 'error', error: { sqlstate: '42P01', message: 'relation "nope" does not exist', position: 15, hint: 'Check the name.' }, results: [] }),
    });
    expect(screen.getByRole('alert')).toHaveTextContent('42P01');
    expect(screen.getByText('Hint: Check the name.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Show position 15 in the editor' }));
    expect(onShowPosition).toHaveBeenCalledWith(15);
  });

  it('explains timeouts, cancels and unknown outcomes', () => {
    const { unmount } = view({ result: doc({ kind: 'timeout', cancelOutcome: 'escalated_terminated', sessionEnded: true, results: [] }) });
    expect(screen.getByRole('alert')).toHaveTextContent('Stopped: the time limit was reached');
    expect(screen.getByRole('alert')).toHaveTextContent(describeCancelOutcome('escalated_terminated'));
    expect(screen.getByText('Session closed')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeDisabled();
    unmount();
    const second = view({ result: doc({ kind: 'cancelled', cancelOutcome: 'confirmed', results: [] }) });
    expect(screen.getByRole('alert')).toHaveTextContent('Cancelled');
    expect(screen.getByRole('alert')).toHaveTextContent('PostgreSQL stopped the statement.');
    second.unmount();
    view({ result: doc({ kind: 'indeterminate', message: 'The connection was lost.', results: [] }) });
    expect(screen.getByRole('alert')).toHaveTextContent('Outcome unknown');
  });

  it('shows the transaction state', () => {
    const { unmount } = view({ result: doc({ kind: 'command', results: [], transaction: 'OPEN' }) });
    expect(screen.getByText('Transaction open')).toBeInTheDocument();
    unmount();
    view({ result: doc({ kind: 'error', error: { sqlstate: '25P02', message: 'aborted' }, results: [], transaction: 'FAILED' }) });
    expect(screen.getByText(/Transaction failed/)).toBeInTheDocument();
  });

  it('shows the running state with Cancel and the lock wait', async () => {
    const { onCancel } = view({ running: true, elapsedMs: 3210, lockWait: true, waitEvent: 'transactionid' });
    expect(screen.getByRole('status')).toHaveTextContent('Running… 3.2 s');
    expect(screen.getByText('Waiting for a lock held by another session')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalled();
  });

  it('explains refusals and offers the syntax error position', async () => {
    const error = { code: 'SYNTAX_ERROR', message: 'Syntax error: syntax error at or near "SELEC"', details: { position: 1 }, requestId: 'r1' };
    const { onShowPosition, unmount } = view({ error });
    expect(screen.getByRole('alert')).toHaveTextContent('Not run');
    await userEvent.click(screen.getByRole('button', { name: 'Show position 1 in the editor' }));
    expect(onShowPosition).toHaveBeenCalledWith(1);
    unmount();
    view({ error: { code: 'AUDIT_UNAVAILABLE', message: 'x' } });
    expect(screen.getByRole('alert')).toHaveTextContent('audit database is unavailable');
  });

  it('Refresh runs the same statement again', async () => {
    const { onRefresh } = view({ result: doc({ kind: 'command', results: [] }), lastText: 'SELECT 1' });
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(onRefresh).toHaveBeenCalled();
  });
});

describe('ResultGrid', () => {
  it('sorts locally, says so, and pages through rows', async () => {
    const rnd = seededRandom('grid');
    const n = 450 + Math.floor(rnd() * 100);
    const rows = Array.from({ length: n }, (_, i) => [String((i * 7919) % n), randomText(rnd, 5)]);
    render(<ResultGrid result={rowsResult([{ name: 'id', type: 'int4' }, { name: 't', type: 'text' }], rows)} />);
    expect(screen.getByText(`Rows 1–200 of ${n.toLocaleString('en-GB')}`)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /id int4/ }));
    expect(screen.getByText(/Sorted locally/)).toBeInTheDocument();
    const firstCell = () => screen.getAllByRole('row')[1].querySelectorAll('td')[1].textContent;
    expect(firstCell()).toBe('0'); // numeric order, not text order
    await userEvent.click(screen.getByRole('button', { name: /id int4/ }));
    expect(firstCell()).toBe(String(n - 1));
    expect(screen.getByRole('columnheader', { name: /id int4/ })).toHaveAttribute('aria-sort', 'descending');
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText(`Rows 201–400 of ${n.toLocaleString('en-GB')}`)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Previous' }));
    await userEvent.click(screen.getByRole('button', { name: /id int4/ }));
    expect(screen.queryByText(/Sorted locally/)).toBeNull();
  });

  it('compares NULLs last, numbers numerically and text by code point', () => {
    expect([null, '10', '9'].sort((a, b) => compareCells(a, b, 'int4'))).toEqual(['9', '10', null]);
    expect(['b', null, 'a'].sort((a, b) => compareCells(a, b, 'text'))).toEqual(['a', 'b', null]);
    expect(compareCells('x', 'x', 'text')).toBe(0);
    expect(compareCells('1e3', 'abc', 'numeric')).toBe(-1);
  });

  it('keeps duplicate column names apart', () => {
    render(<ResultGrid result={rowsResult([{ name: 'a', type: 'int4' }, { name: 'a', type: 'int4' }], [['1', '2']])} />);
    expect(screen.getAllByRole('columnheader', { name: /^a int4$/ })).toHaveLength(2);
    expect(within(screen.getAllByRole('row')[1]).getByText('2')).toBeInTheDocument();
  });
});

describe('useExecution', () => {
  it('runs, reports elapsed time and lock waits, and cancels', async () => {
    let release;
    let cancelled = false;
    server.use(
      http.post(`/api/db-sessions/${SID}/execute`, async ({ request }) => {
        const body = await request.json();
        await new Promise((r) => {
          release = r;
        });
        return HttpResponse.json(doc({ kind: cancelled ? 'cancelled' : 'command', cancelOutcome: cancelled ? 'confirmed' : undefined, results: [], sql: body.sql }));
      }),
      http.get(`/api/db-sessions/${SID}/activity`, () => HttpResponse.json({ running: true, lockWait: true, waitEvent: 'tuple' })),
      http.post(`/api/db-sessions/${SID}/cancel`, () => {
        cancelled = true;
        release();
        return HttpResponse.json({ status: 'cancel_requested' });
      }),
    );
    const { result } = renderHook(() => useExecution(SID, { pollMs: 20, tickMs: 10 }));
    act(() => {
      result.current.run('SELECT pg_sleep(10)', 4);
    });
    expect(result.current.running).toBe(true);
    await waitFor(() => expect(result.current.lockWait).toBe(true));
    await waitFor(() => expect(result.current.elapsedMs).toBeGreaterThan(0));
    act(() => {
      result.current.run('SELECT 2'); // ignored while running
    });
    await act(() => result.current.cancel());
    await waitFor(() => expect(result.current.running).toBe(false));
    expect(result.current.result.kind).toBe('cancelled');
    expect(result.current.lastText).toBe('SELECT pg_sleep(10)');
    expect(result.current.lastFrom).toBe(4);
  });

  it('refresh runs the last text again; refusals are errors; an ended session is reported', async () => {
    const seen = [];
    server.use(
      http.post(`/api/db-sessions/${SID}/execute`, async ({ request }) => {
        const { sql } = await request.json();
        seen.push(sql);
        if (sql === 'bad') return envelope(400, 'MULTIPLE_STATEMENTS', 'Run one statement at a time (found 2).');
        if (sql === 'gone') return envelope(410, 'SESSION_LOST', 'The database session has ended (lost).');
        if (sql === 'killed') return HttpResponse.json(doc({ kind: 'timeout', cancelOutcome: 'escalated_terminated', sessionEnded: true, results: [] }));
        return HttpResponse.json(doc({ kind: 'command', results: [] }));
      }),
    );
    const onSessionEnded = vi.fn();
    const { result } = renderHook(() => useExecution(SID, { onSessionEnded }));
    await act(() => result.current.run('SELECT 1'));
    await act(async () => result.current.refresh());
    await waitFor(() => expect(seen).toEqual(['SELECT 1', 'SELECT 1']));
    await act(() => result.current.run('bad'));
    expect(result.current.error.code).toBe('MULTIPLE_STATEMENTS');
    expect(describeExecutionError(result.current.error)).toMatch(/Select one statement/);
    expect(onSessionEnded).not.toHaveBeenCalled();
    await act(() => result.current.run('killed'));
    expect(onSessionEnded).toHaveBeenCalledTimes(1);
    await act(() => result.current.run('gone'));
    expect(onSessionEnded).toHaveBeenCalledTimes(2);
  });

  it('does nothing without a session', async () => {
    const { result } = renderHook(() => useExecution(null));
    await act(() => result.current.run('SELECT 1'));
    expect(result.current.running).toBe(false);
    await act(async () => result.current.refresh());
    expect(result.current.result).toBeNull();
  });

  it('a failed cancel or activity poll is harmless', async () => {
    let release;
    server.use(
      http.post(`/api/db-sessions/${SID}/execute`, async () => {
        await new Promise((r) => {
          release = r;
        });
        return HttpResponse.json(doc({ kind: 'command', results: [] }));
      }),
      http.get(`/api/db-sessions/${SID}/activity`, () => envelope(500, 'INTERNAL', 'x')),
      http.post(`/api/db-sessions/${SID}/cancel`, () => envelope(409, 'NOT_RUNNING', 'No statement is running')),
    );
    const { result } = renderHook(() => useExecution(SID, { pollMs: 10 }));
    act(() => {
      result.current.run('SELECT 1');
    });
    await act(() => new Promise((r) => setTimeout(r, 40)));
    await act(() => result.current.cancel());
    expect(result.current.lockWait).toBe(false);
    await act(async () => release());
    await waitFor(() => expect(result.current.result?.kind).toBe('command'));
  });
});

describe('describe helpers', () => {
  it('cover every code', () => {
    for (const code of ['EMPTY_STATEMENT', 'TOO_LARGE', 'UNSUPPORTED', 'GATE_DISAGREEMENT', 'SESSION_BUSY', 'EXECUTION_DISABLED', 'SESSION_LOST', 'NOT_FOUND', 'OTHER']) {
      expect(describeExecutionError({ code, message: 'm' })).toBeTruthy();
    }
    expect(describeExecutionError(null)).toBe('The statement could not be run.');
    expect(describeCancelOutcome('unconfirmed')).toMatch(/may still run/);
    expect(describeCancelOutcome(undefined)).toBeNull();
  });
});

describe('commandSummary', () => {
  it('words counts', () => {
    expect(commandSummary([])).toBe('Completed.');
    expect(commandSummary([{ kind: 'command', rowCount: 1 }])).toBe('Completed. 1 row affected.');
    expect(commandSummary([{ kind: 'command', rowCount: 0 }])).toBe('Completed. 0 rows affected.');
    expect(commandSummary([{ kind: 'command', rowCount: 2 }, { kind: 'command', rowCount: 3 }])).toBe('Completed. 2, 3 rows affected.');
  });
});
