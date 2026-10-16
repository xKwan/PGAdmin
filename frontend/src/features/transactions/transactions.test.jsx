import { act, render, renderHook, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { describeExecutionError, describeReasons, explainSqlstate, transaction } from '../../api/execution.js';
import Workspace from '../../app/Workspace.jsx';
import { envelope, http, HttpResponse, server } from '../../mocks/server.js';
import { axeViolations } from '../../test/a11y.js';
import SessionBar from '../connections/SessionBar.jsx';
import ResultView from '../results/ResultView.jsx';
import useExecution from '../results/useExecution.js';
import TransactionBar, { LifetimeWarning } from './TransactionBar.jsx';

const SID = 'AbCdEfGhIjKlMnOpQrStUv';
const SESSION = { id: SID, profile: 'external', database: 'sales', role: 'alice', serverVersion: '18.6', backendPid: 7, companion: true, state: 'OPEN', transaction: 'IDLE' };

function doc(over = {}) {
  return { executionId: 'e', statementClass: 'transaction', kind: 'command', results: [{ kind: 'command' }], transaction: 'IDLE', durationMs: 1, retrievedAt: '2026-10-16T08:00:00Z', ...over };
}

function bar(tx, extra = {}) {
  const execution = { tx: { state: 'IDLE', mode: 'auto', isolation: '', readOnly: false, lifetime: null, begunWith: null, ...tx }, running: false, setMode: vi.fn(), setIsolation: vi.fn(), setReadOnly: vi.fn(), begin: vi.fn(), commit: vi.fn(), rollback: vi.fn(), ...extra };
  const r = render(<TransactionBar execution={execution} />);
  return { ...r, execution };
}

describe('TransactionBar', () => {
  it('in auto-commit mode only shows the state and the mode switch', async () => {
    const { execution, container } = bar({});
    expect(screen.getByText('No transaction')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Begin' })).toBeNull();
    await userEvent.click(screen.getByRole('checkbox', { name: 'Manual commit' }));
    expect(execution.setMode).toHaveBeenCalledWith('manual');
    expect(await axeViolations(container)).toEqual([]);
  });

  it('in manual mode offers isolation, read-only (a convenience) and Begin', async () => {
    const { execution } = bar({ mode: 'manual' });
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Isolation' }), 'serializable');
    expect(execution.setIsolation).toHaveBeenCalledWith('serializable');
    const ro = screen.getByRole('checkbox', { name: 'Read-only (convenience)' });
    expect(ro.closest('label')).toHaveAttribute('title', expect.stringMatching(/not a security control/));
    await userEvent.click(ro);
    expect(execution.setReadOnly).toHaveBeenCalledWith(true);
    await userEvent.click(screen.getByRole('button', { name: 'Begin' }));
    expect(execution.begin).toHaveBeenCalled();
    await userEvent.click(screen.getByRole('checkbox', { name: 'Manual commit' }));
    expect(execution.setMode).toHaveBeenCalledWith('auto');
  });

  it('with an open transaction offers Commit and Rollback; a failed one only Rollback', async () => {
    const open = bar({ state: 'OPEN', begunWith: { isolation: 'repeatable read', readOnly: true } });
    expect(screen.getByText('Transaction open')).toBeInTheDocument();
    expect(screen.getByText('repeatable read, read-only')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Commit' }));
    expect(open.execution.commit).toHaveBeenCalled();
    open.unmount();
    const failed = bar({ state: 'FAILED' });
    expect(screen.getByText('Transaction failed')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Commit' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Rollback' }));
    expect(failed.execution.rollback).toHaveBeenCalled();
  });

  it('is disabled while a statement runs', () => {
    bar({ state: 'OPEN' }, { running: true });
    expect(screen.getByRole('button', { name: 'Rollback' })).toBeDisabled();
    expect(screen.getByRole('checkbox', { name: 'Manual commit' })).toBeDisabled();
  });
});

describe('LifetimeWarning', () => {
  it('warns about idle transactions, the maximum age and idle sessions, and stays quiet otherwise', () => {
    const { rerender } = render(<LifetimeWarning lifetime={{ warning: false, closesAt: '2026-10-16T08:10:00Z', closeReason: 'idle' }} transaction="IDLE" />);
    expect(screen.queryByRole('alert')).toBeNull();
    rerender(<LifetimeWarning lifetime={{ warning: true, closesAt: '2026-10-16T08:10:00Z', closeReason: 'idle_in_transaction' }} transaction="OPEN" />);
    expect(screen.getByRole('alert')).toHaveTextContent(/will be rolled back and the session closed/);
    rerender(<LifetimeWarning lifetime={{ warning: true, closesAt: '2026-10-16T09:00:00Z', closeReason: 'max_transaction_age' }} transaction="OPEN" />);
    expect(screen.getByRole('alert')).toHaveTextContent(/maximum age/);
    rerender(<LifetimeWarning lifetime={{ warning: true, closesAt: 'soon', closeReason: 'idle' }} transaction="IDLE" />);
    expect(screen.getByRole('alert')).toHaveTextContent('This session has been idle and will be closed at soon.');
    rerender(<LifetimeWarning lifetime={null} transaction="IDLE" />);
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

describe('useExecution transactions', () => {
  it('manual mode begins a transaction with the chosen options before the first statement', async () => {
    const calls = [];
    server.use(
      http.post(`/api/db-sessions/${SID}/transaction`, async ({ request }) => {
        const b = await request.json();
        calls.push(['tx', b]);
        return HttpResponse.json(doc({ transaction: b.action === 'begin' ? 'OPEN' : 'IDLE' }));
      }),
      http.post(`/api/db-sessions/${SID}/execute`, async ({ request }) => {
        calls.push(['exec', await request.json()]);
        return HttpResponse.json(doc({ statementClass: 'dml', transaction: 'OPEN', results: [{ kind: 'command', rowCount: 1 }] }));
      }),
    );
    const { result } = renderHook(() => useExecution(SID));
    act(() => {
      result.current.setMode('manual');
      result.current.setIsolation('repeatable read');
      result.current.setReadOnly(true);
    });
    await act(() => result.current.run('UPDATE t SET a = 1 WHERE b = 2'));
    expect(calls).toEqual([
      ['tx', { action: 'begin', readOnly: true, isolation: 'repeatable read' }],
      ['exec', { sql: 'UPDATE t SET a = 1 WHERE b = 2' }],
    ]);
    expect(result.current.tx.state).toBe('OPEN');
    expect(result.current.tx.begunWith).toEqual({ isolation: 'repeatable read', readOnly: true });
    await act(() => result.current.run('SELECT 1'));
    expect(calls).toHaveLength(3); // already in a transaction: no second BEGIN
    await act(() => result.current.commit());
    expect(calls.at(-1)).toEqual(['tx', { action: 'commit', readOnly: false }]);
    expect(result.current.tx.state).toBe('IDLE');
    expect(result.current.tx.begunWith).toBeNull();
  });

  it('a failed BEGIN stops the statement from running', async () => {
    let executed = false;
    server.use(
      http.post(`/api/db-sessions/${SID}/transaction`, () => envelope(503, 'AUDIT_UNAVAILABLE', 'down')),
      http.post(`/api/db-sessions/${SID}/execute`, () => {
        executed = true;
        return HttpResponse.json(doc());
      }),
    );
    const { result } = renderHook(() => useExecution(SID));
    act(() => result.current.setMode('manual'));
    await act(() => result.current.run('SELECT 1'));
    expect(executed).toBe(false);
    expect(result.current.error.code).toBe('AUDIT_UNAVAILABLE');
  });

  it('asks for confirmation, then sends the statement again confirmed; or forgets it', async () => {
    const bodies = [];
    server.use(
      http.post(`/api/db-sessions/${SID}/execute`, async ({ request }) => {
        const b = await request.json();
        bodies.push(b);
        if (!b.confirmed) {
          return HttpResponse.json({ error: { code: 'CONFIRMATION_REQUIRED', message: 'Confirm', reasons: ['no_where'], statementClass: 'dml', requestId: 'r' } }, { status: 409 });
        }
        return HttpResponse.json(doc({ statementClass: 'dml', results: [{ kind: 'command', rowCount: 3 }] }));
      }),
    );
    const { result } = renderHook(() => useExecution(SID));
    await act(() => result.current.run('DELETE FROM t', 5));
    expect(result.current.confirm).toEqual({ text: 'DELETE FROM t', from: 5, reasons: ['no_where'] });
    expect(result.current.error).toBeNull();
    await act(async () => result.current.confirmRun());
    await waitFor(() => expect(result.current.result?.results[0].rowCount).toBe(3));
    expect(bodies).toEqual([{ sql: 'DELETE FROM t' }, { sql: 'DELETE FROM t', confirmed: true }]);
    await act(() => result.current.run('DELETE FROM t'));
    act(() => result.current.dismissConfirm());
    expect(result.current.confirm).toBeNull();
    act(() => result.current.confirmRun()); // nothing to confirm: no request
    expect(bodies).toHaveLength(3);
  });

  it('takes the transaction state and the close deadline from the heartbeat', () => {
    const { result } = renderHook(() => useExecution(SID));
    act(() => result.current.observeHeartbeat({ transaction: 'OPEN', closesAt: '2026-10-16T08:10:00Z', closeReason: 'idle_in_transaction', warning: true }));
    expect(result.current.tx.state).toBe('OPEN');
    expect(result.current.tx.lifetime).toEqual({ closesAt: '2026-10-16T08:10:00Z', closeReason: 'idle_in_transaction', warning: true });
    act(() => result.current.observeHeartbeat({ state: 'OPEN' }));
    expect(result.current.tx.lifetime).toBeNull();
    expect(result.current.tx.state).toBe('OPEN');
  });
});

describe('ResultView explanations', () => {
  const idle = { running: false, result: null, error: null, elapsedMs: 0, lockWait: false, waitEvent: null, cancelling: false, lastText: 'x' };

  it('explains SQLSTATEs, shows the COMMIT notice and the snapshot note', () => {
    const { rerender } = render(
      <ResultView
        execution={{ ...idle, result: doc({ kind: 'error', results: [], error: { sqlstate: '23505', message: 'duplicate key', constraint: 'acct_pkey' }, transaction: 'FAILED' }) }}
        onCancel={() => {}}
        onRefresh={() => {}}
        onShowPosition={() => {}}
      />,
    );
    expect(screen.getByText('A constraint was violated: acct_pkey.')).toBeInTheDocument();
    rerender(
      <ResultView
        execution={{ ...idle, result: doc({ notice: 'The transaction had failed, so COMMIT rolled it back. Nothing was saved.' }), tx: { state: 'IDLE', begunWith: null } }}
        onCancel={() => {}}
        onRefresh={() => {}}
        onShowPosition={() => {}}
      />,
    );
    expect(screen.getByText(/COMMIT rolled it back/)).toBeInTheDocument();
    rerender(
      <ResultView
        execution={{ ...idle, result: doc({ transaction: 'OPEN' }), tx: { state: 'OPEN', begunWith: { isolation: 'repeatable read', readOnly: false } } }}
        onCancel={() => {}}
        onRefresh={() => {}}
        onShowPosition={() => {}}
      />,
    );
    expect(screen.getByText('Inside this repeatable read transaction, Refresh shows the same snapshot.')).toBeInTheDocument();
  });

  it('has a sentence for every common code', () => {
    for (const code of ['55P03', '40P01', '40001', '25P02', '25001', '25006', '23503']) expect(explainSqlstate(code)).toBeTruthy();
    expect(explainSqlstate('42P01')).toBeNull();
    expect(explainSqlstate(undefined)).toBeNull();
    expect(describeReasons(['drop', 'something_new'])).toEqual(['drops an object', 'something_new']);
    expect(describeReasons()).toEqual([]);
    expect(describeExecutionError({ code: 'CONFIRMATION_REQUIRED', details: { reasons: ['truncate'] } })).toBe('This statement empties a table. It was not run.');
    expect(describeExecutionError({ code: 'CONFIRMATION_REQUIRED' })).toBe('This statement needs a confirmation. It was not run.');
    expect(describeExecutionError({ code: 'INVALID_ISOLATION', message: 'bad' })).toBe('bad');
  });

  it('sends transaction actions with their options', async () => {
    let body;
    server.use(
      http.post(`/api/db-sessions/${SID}/transaction`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(doc());
      }),
    );
    await transaction(SID, 'rollback');
    expect(body).toEqual({ action: 'rollback', readOnly: false });
  });
});

describe('Workspace transactions', () => {
  async function connect(profiles) {
    server.use(
      http.post('/api/profiles/external/databases', () => HttpResponse.json({ databases: [{ name: 'sales', owner: 'alice', owned: true }] })),
      http.post('/api/db-sessions/external', () => HttpResponse.json(SESSION, { status: 201 })),
    );
    if (profiles) server.use(http.get('/api/profiles', () => HttpResponse.json({ profiles })));
    render(<Workspace profile="external" executionEnabled onBack={() => {}} />);
    await userEvent.click(screen.getByRole('button', { name: 'Show my databases' }));
    await userEvent.click(await screen.findByRole('button', { name: /sales/ }));
    return screen.findByRole('textbox', { name: 'SQL editor' });
  }

  it('shows the profile banner, confirms destructive statements and locks Run after a failure', async () => {
    let call = 0;
    server.use(
      http.post(`/api/db-sessions/${SID}/execute`, async ({ request }) => {
        const b = await request.json();
        call += 1;
        if (!b.confirmed) {
          return HttpResponse.json({ error: { code: 'CONFIRMATION_REQUIRED', message: 'Confirm', reasons: ['drop'], requestId: 'r' } }, { status: 409 });
        }
        return HttpResponse.json(doc({ kind: 'error', results: [], error: { sqlstate: '40P01', message: 'deadlock detected' }, transaction: 'FAILED' }));
      }),
      http.post(`/api/db-sessions/${SID}/transaction`, () => HttpResponse.json(doc({ transaction: 'IDLE', statementClass: 'transaction' }))),
    );
    const editor = await connect([{ name: 'external', displayName: 'X', bannerText: 'PRODUCTION', bannerTone: 'danger' }]);
    expect(await screen.findByRole('note')).toHaveTextContent('PRODUCTION');
    await userEvent.click(editor);
    await userEvent.keyboard('DROP TABLE t');
    await userEvent.click(screen.getByRole('button', { name: 'Run' }));
    const dialog = await screen.findByRole('dialog', { name: 'Run this statement?' });
    expect(dialog).toHaveTextContent('This statement drops an object.');
    expect(dialog).toHaveTextContent(/not a security control/);
    await userEvent.click(screen.getByRole('button', { name: 'Run it' }));
    expect(await screen.findByText(/Deadlock detected/)).toBeInTheDocument();
    expect(call).toBe(2);
    expect(screen.getByRole('button', { name: 'Run' })).toBeDisabled();
    expect(screen.getByText('The transaction failed: roll back to continue')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Rollback' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Run' })).toBeEnabled());
  });

  it('cancelling the confirmation runs nothing', async () => {
    let calls = 0;
    server.use(
      http.post(`/api/db-sessions/${SID}/execute`, () => {
        calls += 1;
        return HttpResponse.json({ error: { code: 'CONFIRMATION_REQUIRED', message: 'Confirm', reasons: ['truncate'], requestId: 'r' } }, { status: 409 });
      }),
    );
    const editor = await connect();
    await userEvent.click(editor);
    await userEvent.keyboard('TRUNCATE t');
    await userEvent.click(screen.getByRole('button', { name: 'Run' }));
    await screen.findByRole('dialog', { name: 'Run this statement?' });
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(calls).toBe(1);
  });

  it('works without the profile list', async () => {
    server.use(http.get('/api/profiles', () => envelope(500, 'INTERNAL', 'x')));
    await connect();
    expect(screen.queryByRole('note')).toBeNull();
  });
});

describe('SessionBar with an open transaction', () => {
  it('asks before disconnecting, and passes heartbeats on', async () => {
    let closed = 0;
    server.use(
      http.delete(`/api/db-sessions/${SID}`, () => {
        closed += 1;
        return new HttpResponse(null, { status: 204 });
      }),
      http.post(`/api/db-sessions/${SID}/heartbeat`, () => HttpResponse.json({ state: 'OPEN', transaction: 'OPEN', closesAt: 'x', closeReason: 'idle_in_transaction', warning: false })),
    );
    const onClosed = vi.fn();
    const onHeartbeat = vi.fn();
    render(<SessionBar session={SESSION} onClosed={onClosed} onHeartbeat={onHeartbeat} transactionOpen heartbeatMs={20} />);
    await waitFor(() => expect(onHeartbeat).toHaveBeenCalledWith(expect.objectContaining({ transaction: 'OPEN' })));
    await userEvent.click(screen.getByRole('button', { name: 'Disconnect' }));
    expect(screen.getByRole('dialog', { name: 'Disconnect with an open transaction?' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(closed).toBe(0);
    await userEvent.click(screen.getByRole('button', { name: 'Disconnect' }));
    await userEvent.click(screen.getByRole('button', { name: 'Disconnect and roll back' }));
    await waitFor(() => expect(onClosed).toHaveBeenCalled());
    expect(closed).toBe(1);
  });
});
