import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse, server } from '../../mocks/server.js';
import { ExportButtons, stamp } from './ExportButtons.jsx';

const SID = 'AbCdEfGhIjKlMnOpQrStUv';

function read(blob) {
  return new Promise((resolve) => {
    const f = new FileReader();
    f.onload = () => resolve(f.result);
    f.readAsText(blob);
  });
}
const RESULT = {
  kind: 'rows',
  executionId: '00000000-0000-4000-8000-000000000009',
  results: [{ columns: [{ name: 'id', type: 'int4' }, { name: 'formula', type: 'text' }], rows: [['1', '=1+1'], ['2', null]], rowCount: 2 }],
};

describe('export (admin Phase 7)', () => {
  let saved;
  beforeEach(() => {
    saved = [];
    URL.createObjectURL = vi.fn((blob) => {
      saved.push(blob);
      return 'blob:http://localhost/x';
    });
    URL.revokeObjectURL = vi.fn();
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function click() {
      saved.push(this.download);
    });
  });
  afterEach(() => vi.restoreAllMocks());

  it('records the export first, then saves the rows on screen as CSV or JSON', async () => {
    const posts = [];
    server.use(
      http.post(`/api/db-sessions/${SID}/export`, async ({ request }) => {
        posts.push(await request.json());
        return HttpResponse.json({ recorded: true });
      }),
    );
    render(<ExportButtons sessionId={SID} result={RESULT} />);
    await userEvent.click(screen.getByRole('button', { name: 'Export CSV' }));
    await vi.waitFor(() => expect(saved).toHaveLength(2));
    expect(posts).toEqual([{ executionId: RESULT.executionId, format: 'csv', rows: 2, columns: 2 }]);
    expect(await read(saved[0])).toBe("id,formula\r\n1,'=1+1\r\n2,\r\n");
    expect(saved[1]).toMatch(/^result-\d{8}-\d{6}\.csv$/);
    await userEvent.click(screen.getByRole('button', { name: 'Export JSON' }));
    await vi.waitFor(() => expect(saved).toHaveLength(4));
    expect(JSON.parse(await read(saved[2]))).toEqual([{ id: '1', formula: '=1+1' }, { id: '2', formula: null }]);
    expect(posts[1].format).toBe('json');
  });

  it('saves nothing when the export cannot be recorded', async () => {
    server.use(http.post(`/api/db-sessions/${SID}/export`, () => HttpResponse.json({ error: { code: 'AUDIT_UNAVAILABLE', message: 'x' } }, { status: 503 })));
    render(<ExportButtons sessionId={SID} result={{ ...RESULT, results: [{ ...RESULT.results[0], truncatedCells: [[0, 1]] }] }} />);
    expect(screen.getByText('Values cut for display are exported as shown.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Export CSV' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('the audit database is unavailable');
    expect(saved).toEqual([]);
    server.use(http.post(`/api/db-sessions/${SID}/export`, () => HttpResponse.json({ error: { code: 'NOT_FOUND', message: 'Not found' } }, { status: 404 })));
    await userEvent.click(screen.getByRole('button', { name: 'Export JSON' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Not exported: Not found');
  });

  it('names files by local time', () => {
    expect(stamp(new Date(2026, 9, 20, 9, 5, 7))).toBe('20261020-090507');
  });
});
