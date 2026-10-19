import { act, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { describeExecutionError, describeScriptStop } from '../../api/execution.js';
import { envelope, http, HttpResponse, server } from '../../mocks/server.js';
import { axeViolations } from '../../test/a11y.js';
import ResultGrid from './ResultGrid.jsx';
import ResultView from './ResultView.jsx';
import useExecution, { appendPage } from './useExecution.js';

const SID = 'AbCdEfGhIjKlMnOpQrStUv';
const AT = '2026-10-19T08:00:00Z';

function rows(from, to) {
  const out = [];
  for (let i = from; i <= to; i += 1) out.push([String(i)]);
  return out;
}

function rowsDoc({ executionId = 'e1', first = 1, last = 100, more = false, transaction = 'OPEN', truncatedBy, extra = {} } = {}) {
  const r = { kind: 'rows', columns: [{ name: 'g', type: 'int4' }], rows: rows(first, last), rowCount: last - first + 1, truncated: Boolean(more || truncatedBy) };
  if (more) r.more = true;
  if (truncatedBy) r.truncatedBy = truncatedBy;
  return { executionId, statementClass: 'select', kind: 'rows', transaction, durationMs: 2, retrievedAt: AT, results: [r], ...extra };
}

const SCRIPT = {
  kind: 'script',
  scriptId: 's1',
  statementClass: 'script',
  total: 4,
  completed: 2,
  stopReason: 'error',
  stoppedAt: 2,
  transaction: 'IDLE',
  durationMs: 9,
  retrievedAt: AT,
  statements: [
    { index: 0, offset: 0, length: 20, statementClass: 'ddl', executionId: 'x0', kind: 'command', results: [{ kind: 'command' }], durationMs: 3 },
    { index: 1, offset: 22, length: 15, statementClass: 'select', executionId: 'x1', kind: 'rows', durationMs: 1,
      results: [{ kind: 'rows', columns: [{ name: 'n', type: 'int4' }], rows: [['7']], rowCount: 1, truncated: false }] },
    { index: 2, offset: 39, length: 12, statementClass: 'select', executionId: 'x2', kind: 'error', durationMs: 1,
      error: { sqlstate: '22012', message: 'division by zero', position: 8 }, results: [] },
    { index: 3, offset: 53, length: 9, statementClass: 'dml', kind: 'not_run' },
  ],
};

function view(execution, props = {}) {
  const base = { running: false, result: null, error: null, elapsedMs: 0, lockWait: false, cancelling: false, lastText: 'x', lastFrom: 0, lastScript: false, tx: { state: 'IDLE' } };
  return render(<ResultView execution={{ ...base, ...execution }} onCancel={() => {}} onRefresh={() => {}} onShowPosition={() => {}} {...props} />);
}

describe('script results', () => {
  it('lists every statement with its outcome and where the script stopped', async () => {
    const onSelect = vi.fn();
    const onShowPosition = vi.fn();
    const { container } = view({ result: SCRIPT, lastScript: true }, { onSelect, onShowPosition });
    expect(screen.getByText(/Stopped at statement 3 of 4: it failed. 2 completed before it; the rest were not run./)).toBeInTheDocument();
    const items = within(screen.getByRole('list', { name: 'Script statements' })).getAllByRole('listitem');
    expect(items).toHaveLength(4);
    expect(within(items[0]).getByText('Completed')).toBeInTheDocument();
    expect(within(items[1]).getByText('7', { selector: 'td' })).toBeInTheDocument();
    expect(within(items[2]).getByText('Failed')).toBeInTheDocument();
    expect(within(items[2]).getByText('division by zero')).toBeInTheDocument();
    expect(within(items[3]).getByText('Not run', { selector: '.badge' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Show statement 4 in the editor' }));
    expect(onSelect).toHaveBeenCalledWith(53, 9);
    // An error position is relative to its statement; the page gets the statement's offset with it.
    await userEvent.click(within(items[2]).getByRole('button', { name: 'Show position 8 in the editor' }));
    expect(onShowPosition).toHaveBeenCalledWith(8, 39);
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeEnabled();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('says when every statement completed, and why a statement was not run', () => {
    const ok = { ...SCRIPT, completed: 4, stopReason: undefined, stoppedAt: undefined, total: 1, statements: [SCRIPT.statements[0]] };
    const { unmount } = view({ result: ok });
    expect(screen.getByText('All 1 statement completed.')).toBeInTheDocument();
    unmount();
    const audit = {
      ...SCRIPT,
      completed: 0,
      stopReason: 'audit_unavailable',
      stoppedAt: 0,
      total: 1,
      statements: [{ index: 0, offset: 0, length: 8, statementClass: 'select', kind: 'not_run', error: { code: 'AUDIT_UNAVAILABLE', message: 'The audit database is unavailable, so this statement was not run.' } }],
    };
    view({ result: audit });
    expect(screen.getByText(/the audit database was unavailable/)).toBeInTheDocument();
    expect(screen.getByText('The audit database is unavailable, so this statement was not run.')).toBeInTheDocument();
  });

  it('describes every way a script can stop', () => {
    for (const reason of ['error', 'timeout', 'cancelled', 'indeterminate', 'settings_changed', 'audit_unavailable', 'session_ended']) {
      expect(describeScriptStop(reason)).not.toBe(reason);
    }
    expect(describeScriptStop('something_new')).toBe('something_new');
    expect(describeExecutionError({ code: 'TOO_MANY_STATEMENTS', message: 'The script has 6 statements; at most 5 can run as one script' })).toMatch(/Split it/);
    expect(describeExecutionError({ code: 'CURSOR_CLOSED' })).toMatch(/no longer available/);
    expect(describeExecutionError({ code: 'MULTIPLE_STATEMENTS', message: 'Run one statement at a time (found 2).' })).toMatch(/Run script/);
  });

  it('a refused script says nothing ran; progress shows while it runs', () => {
    const { unmount } = view({ error: { code: 'UNSUPPORTED', message: 'Statement 2: COPY …', details: { statementIndex: 1, position: 10 } } });
    expect(screen.getByText('Nothing in the script was run.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show position 10 in the editor' })).toBeInTheDocument();
    unmount();
    view({ running: true, lastScript: true, progress: { statement: 2, of: 5 }, elapsedMs: 1500 });
    expect(screen.getByText(/Running script: statement 2 of 5… 1.5 s/)).toBeInTheDocument();
  });
});

describe('further pages (P9-3)', () => {
  it('appends a page, keeping cut cells in the right rows', () => {
    const first = rowsDoc({ more: true, extra: {} });
    first.results[0].truncatedCells = [[3, 0]];
    const page = rowsDoc({ first: 101, last: 150, transaction: 'OPEN' });
    page.results[0].truncatedCells = [[1, 0]];
    const merged = appendPage(first, page);
    expect(merged.results[0].rows).toHaveLength(150);
    expect(merged.results[0].more).toBe(false);
    expect(merged.results[0].truncated).toBe(false);
    expect(merged.results[0].truncatedBy).toBeUndefined();
    expect(merged.results[0].truncatedCells).toEqual([[3, 0], [101, 0]]);
    const capped = appendPage(first, rowsDoc({ first: 101, last: 120, truncatedBy: 'limit' }));
    expect(capped.results[0].truncatedBy).toBe('limit');
    expect(appendPage(first, { results: [] })).toBe(first);
  });

  it('the grid offers Load more, says when the cap is reached, and offers a read-only transaction outside one', async () => {
    const onLoadMore = vi.fn();
    const { unmount } = render(<ResultGrid result={rowsDoc({ more: true }).results[0]} onLoadMore={onLoadMore} />);
    expect(screen.getByText(/100 rows loaded; more rows are waiting on the server/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Load more rows' }));
    expect(onLoadMore).toHaveBeenCalled();
    unmount();
    const r2 = render(<ResultGrid result={rowsDoc({ more: true }).results[0]} onLoadMore={onLoadMore} loadingMore />);
    expect(screen.getByRole('button', { name: 'Loading…' })).toBeDisabled();
    r2.unmount();
    const r3 = render(<ResultGrid result={rowsDoc({ truncatedBy: 'limit' }).results[0]} />);
    expect(screen.getByText(/the most that can be loaded/)).toBeInTheDocument();
    r3.unmount();
    const onRunReadOnly = vi.fn();
    render(<ResultGrid result={rowsDoc({ truncatedBy: 'rows', transaction: 'IDLE' }).results[0]} onRunReadOnly={onRunReadOnly} />);
    await userEvent.click(screen.getByRole('button', { name: 'Run again in a read-only transaction to load more' }));
    expect(onRunReadOnly).toHaveBeenCalled();
  });

  it('loads the next pages into the result until the server has no more', async () => {
    const fetched = [];
    server.use(
      http.post(`/api/db-sessions/${SID}/execute`, () => HttpResponse.json(rowsDoc({ more: true }))),
      http.post(`/api/db-sessions/${SID}/fetch`, async ({ request }) => {
        fetched.push((await request.json()).executionId);
        return HttpResponse.json(fetched.length === 1 ? rowsDoc({ first: 101, last: 200, more: true }) : rowsDoc({ first: 201, last: 230 }));
      }),
    );
    const onResult = vi.fn();
    const { result } = renderHook(() => useExecution(SID, { onResult }));
    await act(() => result.current.run('SELECT g FROM big'));
    await act(() => result.current.loadMore());
    expect(result.current.result.results[0].rows).toHaveLength(200);
    await act(() => result.current.loadMore());
    expect(result.current.result.results[0].rows).toHaveLength(230);
    expect(result.current.result.results[0].more).toBe(false);
    await act(() => result.current.loadMore()); // nothing more: no request
    expect(fetched).toEqual(['e1', 'e1']);
    expect(onResult).toHaveBeenCalledTimes(1); // pages extend a result; they are not new results
  });

  it('a closed cursor ends paging with an explanation', async () => {
    server.use(
      http.post(`/api/db-sessions/${SID}/execute`, () => HttpResponse.json(rowsDoc({ more: true }))),
      http.post(`/api/db-sessions/${SID}/fetch`, () => envelope(409, 'CURSOR_CLOSED', 'gone')),
    );
    const { result } = renderHook(() => useExecution(SID));
    await act(() => result.current.run('SELECT g FROM big'));
    await act(() => result.current.loadMore());
    expect(result.current.fetchError.code).toBe('CURSOR_CLOSED');
    expect(result.current.result.results[0].more).toBe(false);
    expect(result.current.result.results[0].rows).toHaveLength(100);
  });

  it('a lost session while paging is reported', async () => {
    server.use(
      http.post(`/api/db-sessions/${SID}/execute`, () => HttpResponse.json(rowsDoc({ more: true }))),
      http.post(`/api/db-sessions/${SID}/fetch`, () => envelope(410, 'SESSION_LOST', 'lost')),
    );
    const onSessionEnded = vi.fn();
    const { result } = renderHook(() => useExecution(SID, { onSessionEnded }));
    await act(() => result.current.run('SELECT g FROM big'));
    await act(() => result.current.loadMore());
    expect(onSessionEnded).toHaveBeenCalled();
  });

  it('runs the statement again inside a read-only transaction', async () => {
    const sent = [];
    server.use(
      http.post(`/api/db-sessions/${SID}/transaction`, async ({ request }) => {
        sent.push(['tx', await request.json()]);
        return HttpResponse.json({ executionId: 'b', statementClass: 'transaction', kind: 'command', transaction: 'OPEN', durationMs: 1, retrievedAt: AT, results: [{ kind: 'command' }] });
      }),
      http.post(`/api/db-sessions/${SID}/execute`, async ({ request }) => {
        sent.push(['sql', (await request.json()).sql]);
        return HttpResponse.json(rowsDoc({ more: true }));
      }),
    );
    const { result } = renderHook(() => useExecution(SID));
    await act(() => result.current.runInReadOnlyTransaction('SELECT g FROM big', 3));
    expect(sent).toEqual([['tx', { action: 'begin', readOnly: true }], ['sql', 'SELECT g FROM big']]);
    expect(result.current.tx.begunWith).toEqual({ isolation: 'server default', readOnly: true });
    expect(result.current.lastFrom).toBe(3);
  });

  it('the results pane wires Load more and the read-only offer, and shows fetching and fetch errors', async () => {
    const loadMore = vi.fn();
    const runInReadOnlyTransaction = vi.fn();
    const { unmount } = view({ result: rowsDoc({ more: true }), loadMore });
    await userEvent.click(screen.getByRole('button', { name: 'Load more rows' }));
    expect(loadMore).toHaveBeenCalled();
    unmount();
    const r2 = view({ result: rowsDoc({ truncatedBy: 'rows', transaction: 'IDLE' }), runInReadOnlyTransaction, lastText: 'SELECT 1', lastFrom: 7 });
    await userEvent.click(screen.getByRole('button', { name: 'Run again in a read-only transaction to load more' }));
    expect(runInReadOnlyTransaction).toHaveBeenCalledWith('SELECT 1', 7);
    r2.unmount();
    const onCancel = vi.fn();
    view({ result: rowsDoc({ more: true }), fetching: true, fetchError: { code: 'CURSOR_CLOSED' } }, { onCancel });
    expect(screen.getByText(/Loading more rows/)).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent(/no longer available/);
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalled();
  });
});

describe('useExecution scripts', () => {
  it('runs a script, asks once for a destructive one, and reports each run', async () => {
    const sent = [];
    server.use(
      http.post(`/api/db-sessions/${SID}/script`, async ({ request }) => {
        const body = await request.json();
        sent.push(body);
        if (!body.confirmed && body.sql.includes('DROP')) {
          return HttpResponse.json({ error: { code: 'CONFIRMATION_REQUIRED', message: 'confirm', reasons: ['drop'], statements: 2, requestId: 'r' } }, { status: 409 });
        }
        return HttpResponse.json(SCRIPT);
      }),
    );
    const onFinished = vi.fn();
    const { result } = renderHook(() => useExecution(SID, { onFinished }));
    await act(() => result.current.runScript('SELECT 1; DROP TABLE t', 4));
    expect(result.current.confirm).toEqual({ text: 'SELECT 1; DROP TABLE t', from: 4, script: true, reasons: ['drop'] });
    expect(onFinished).not.toHaveBeenCalled(); // asked, not finished
    await act(async () => result.current.confirmRun());
    await waitFor(() => expect(result.current.result?.kind).toBe('script'));
    expect(sent.at(-1)).toEqual({ sql: 'SELECT 1; DROP TABLE t', confirmed: true });
    expect(result.current.lastScript).toBe(true);
    expect(onFinished).toHaveBeenLastCalledWith({ text: 'SELECT 1; DROP TABLE t', script: true, result: SCRIPT, error: null });
    await act(async () => result.current.refresh()); // Refresh runs the script again, as a script
    await waitFor(() => expect(sent).toHaveLength(3));
    expect(sent[2].sql).toBe('SELECT 1; DROP TABLE t');
  });

  it('in manual mode a script begins a transaction first; a refusal is reported to the history', async () => {
    const order = [];
    server.use(
      http.post(`/api/db-sessions/${SID}/transaction`, async () => {
        order.push('begin');
        return HttpResponse.json({ executionId: 'b', statementClass: 'transaction', kind: 'command', transaction: 'OPEN', durationMs: 1, retrievedAt: AT, results: [] });
      }),
      http.post(`/api/db-sessions/${SID}/script`, async () => {
        order.push('script');
        return envelope(400, 'SYNTAX_ERROR', 'Syntax error');
      }),
    );
    const onFinished = vi.fn();
    const { result } = renderHook(() => useExecution(SID, { onFinished }));
    act(() => result.current.setMode('manual'));
    await act(() => result.current.runScript('SELEC 1'));
    expect(order).toEqual(['begin', 'script']);
    expect(result.current.error.code).toBe('SYNTAX_ERROR');
    expect(onFinished).toHaveBeenCalledWith(expect.objectContaining({ script: true, result: null, error: expect.objectContaining({ code: 'SYNTAX_ERROR' }) }));
  });

  it('progress comes from the activity poll', async () => {
    let release;
    server.use(
      http.post(`/api/db-sessions/${SID}/script`, async () => {
        await new Promise((r) => {
          release = r;
        });
        return HttpResponse.json(SCRIPT);
      }),
      http.get(`/api/db-sessions/${SID}/activity`, () => HttpResponse.json({ running: true, scriptStatement: 2, scriptStatements: 4 })),
    );
    const { result } = renderHook(() => useExecution(SID, { pollMs: 20, tickMs: 10 }));
    act(() => {
      result.current.runScript('SELECT 1; SELECT 2');
    });
    await waitFor(() => expect(result.current.progress).toEqual({ statement: 2, of: 4 }));
    await act(async () => release());
    await waitFor(() => expect(result.current.running).toBe(false));
    expect(result.current.progress).toBeNull();
  });
});
