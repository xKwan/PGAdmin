import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { axeViolations } from '../../test/a11y.js';
import HistoryPanel from './HistoryPanel.jsx';
import { addHistory, clearHistory, historyEntry, MAX_ENTRIES, readHistory } from './queryHistory.js';

const SID = 'S-1-5-21-1-2-3-1001';
const NOW = new Date('2026-10-19T08:00:00Z');

afterEach(() => window.sessionStorage.clear());

/** A storage that holds at most `limit` characters, like a full sessionStorage. */
function smallStorage(limit) {
  const data = new Map();
  return {
    sessionStorage: {
      getItem: (k) => (data.has(k) ? data.get(k) : null),
      setItem: (k, v) => {
        if (v.length > limit) {
          const e = new Error('quota');
          e.name = 'QuotaExceededError';
          throw e;
        }
        data.set(k, v);
      },
      removeItem: (k) => data.delete(k),
    },
  };
}

describe('queryHistory', () => {
  it('keeps the newest entries first, per user and server, in this tab only', () => {
    addHistory('external', SID, historyEntry({ text: 'SELECT 1', database: 'sales', now: NOW }));
    addHistory('external', SID, historyEntry({ text: 'SELECT 2', database: 'sales', now: NOW }));
    addHistory('internal', SID, historyEntry({ text: 'SELECT 3', database: 'ops', now: NOW }));
    expect(readHistory('external', SID).map((e) => e.text)).toEqual(['SELECT 2', 'SELECT 1']);
    expect(readHistory('internal', SID).map((e) => e.text)).toEqual(['SELECT 3']);
    expect(readHistory('external', 'S-1-5-21-other')).toEqual([]);
    expect(window.localStorage.length).toBe(0); // never kept beyond the tab
    clearHistory('external', SID);
    expect(readHistory('external', SID)).toEqual([]);
  });

  it(`keeps at most ${MAX_ENTRIES} entries`, () => {
    for (let i = 0; i < MAX_ENTRIES + 5; i += 1) addHistory('external', SID, historyEntry({ text: `SELECT ${i}`, database: 'd', now: NOW }));
    const list = readHistory('external', SID);
    expect(list).toHaveLength(MAX_ENTRIES);
    expect(list[0].text).toBe(`SELECT ${MAX_ENTRIES + 4}`);
  });

  it('drops the oldest entries when storage is full, and works without storage', () => {
    const win = smallStorage(400);
    for (let i = 0; i < 10; i += 1) addHistory('external', SID, historyEntry({ text: `SELECT ${i}`, database: 'd', now: NOW }), win);
    const kept = readHistory('external', SID, win);
    expect(kept.length).toBeGreaterThan(0);
    expect(kept.length).toBeLessThan(10);
    expect(kept[0].text).toBe('SELECT 9');
    // Too large to store at all: still returned for this page.
    expect(addHistory('external', SID, historyEntry({ text: 'x'.repeat(1000), database: 'd', now: NOW }), win)[0].text).toHaveLength(1000);
    const blocked = {
      get sessionStorage() {
        throw new Error('blocked');
      },
    };
    expect(readHistory('external', SID, blocked)).toEqual([]);
    expect(addHistory('external', SID, historyEntry({ text: 'SELECT 1', database: 'd', now: NOW }), blocked)).toHaveLength(1);
    expect(() => clearHistory('external', SID, blocked)).not.toThrow();
    window.sessionStorage.setItem(`pgweb.history.external.${SID}`, '{not json');
    expect(readHistory('external', SID)).toEqual([]);
    window.sessionStorage.setItem(`pgweb.history.external.${SID}`, '{"a":1}');
    expect(readHistory('external', SID)).toEqual([]);
  });

  it('records how each run ended', () => {
    const outcome = (args) => historyEntry({ text: 't', database: 'd', now: NOW, ...args }).outcome;
    expect(outcome({ result: { kind: 'rows' } })).toBe('success');
    expect(outcome({ result: { kind: 'error' } })).toBe('error');
    expect(outcome({ result: { kind: 'timeout' } })).toBe('timeout');
    expect(outcome({ result: { kind: 'cancelled' } })).toBe('cancelled');
    expect(outcome({ result: { kind: 'indeterminate' } })).toBe('unknown');
    expect(outcome({ error: { code: 'SYNTAX_ERROR' } })).toBe('refused');
    expect(outcome({ script: true, result: { kind: 'script', stopReason: 'error' } })).toBe('error');
    expect(outcome({ script: true, result: { kind: 'script', stopReason: 'cancelled' } })).toBe('stopped');
    const e = historyEntry({ text: 'SELECT 1; SELECT 2', database: 'sales', script: true, result: { kind: 'script', total: 2, durationMs: 5 }, now: NOW });
    expect(e).toMatchObject({ at: NOW.toISOString(), database: 'sales', script: true, statements: 2, durationMs: 5, outcome: 'success' });
    expect(historyEntry({ text: 'x', database: 'd', now: NOW }).statements).toBeNull();
  });
});

describe('HistoryPanel', () => {
  const entries = [
    { id: '2', at: NOW.toISOString(), text: '\n  SELECT * FROM orders\nWHERE id = 1', database: 'sales', script: false, outcome: 'success', durationMs: 4 },
    { id: '1', at: NOW.toISOString(), text: `CREATE TABLE t (a int); DROP TABLE t; ${'x'.repeat(200)}`, database: 'sales', script: true, statements: 2, outcome: 'error', durationMs: null },
    { id: '0', at: 'not a date', text: '   ', database: 'ops', script: false, outcome: 'mystery' },
  ];

  it('lists entries, filters them, and inserts or opens their full text', async () => {
    const onInsert = vi.fn();
    const onOpen = vi.fn();
    const { container } = render(<HistoryPanel entries={entries} onInsert={onInsert} onOpen={onOpen} onClear={() => {}} />);
    const items = screen.getAllByRole('listitem');
    expect(items).toHaveLength(3);
    expect(within(items[0]).getByText('SELECT * FROM orders')).toBeInTheDocument();
    expect(within(items[0]).getByText('Completed')).toBeInTheDocument();
    expect(within(items[1]).getByText('Script (2)')).toBeInTheDocument();
    expect(within(items[1]).getByText(/…$/)).toBeInTheDocument();
    expect(within(items[2]).getByText('(empty)')).toBeInTheDocument();
    expect(within(items[2]).getByText('not a date')).toBeInTheDocument();
    await userEvent.click(within(items[0]).getByRole('button', { name: 'Insert' }));
    expect(onInsert).toHaveBeenCalledWith(entries[0].text);
    await userEvent.click(within(items[1]).getByRole('button', { name: 'Open in a new tab' }));
    expect(onOpen).toHaveBeenCalledWith(entries[1].text);
    await userEvent.type(screen.getByRole('searchbox', { name: 'Filter history' }), 'drop');
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    await userEvent.type(screen.getByRole('searchbox', { name: 'Filter history' }), 'zzz');
    expect(screen.getByText('Nothing matches the filter.')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('clears, and says when there is nothing yet', async () => {
    const onClear = vi.fn();
    const { rerender } = render(<HistoryPanel entries={entries} onInsert={() => {}} onOpen={() => {}} onClear={onClear} />);
    await userEvent.click(screen.getByRole('button', { name: 'Clear history' }));
    expect(onClear).toHaveBeenCalled();
    rerender(<HistoryPanel entries={[]} onInsert={() => {}} onOpen={() => {}} onClear={onClear} />);
    expect(screen.getByText('No history yet')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Clear history' })).toBeDisabled();
  });
});
