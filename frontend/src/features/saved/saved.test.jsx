import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse, server } from '../../mocks/server.js';
import { axeViolations } from '../../test/a11y.js';
import { SaveDialog, SavedPanel } from './SavedPanel.jsx';

const ID = '0b6c3f0e-6f43-4a39-9d55-2b0d8a5e1f21';

describe('saved queries (admin Phase 11)', () => {
  it('lists, opens and deletes your saved queries; nothing runs', async () => {
    let queries = [{ id: ID, name: 'Big orders', length: 31, updatedAt: '2026-10-21T08:00:00Z' }];
    const deleted = [];
    server.use(
      http.get('/api/saved-queries', () => HttpResponse.json({ queries, max: 200 })),
      http.get(`/api/saved-queries/${ID}`, () => HttpResponse.json({ id: ID, name: 'Big orders', text: 'SELECT * FROM orders WHERE total > 10' })),
      http.delete(`/api/saved-queries/${ID}`, () => {
        deleted.push(ID);
        queries = [];
        return HttpResponse.json({ deleted: ID });
      }),
    );
    const onOpen = vi.fn();
    const { container } = render(<SavedPanel version={0} onOpen={onOpen} />);
    const table = await screen.findByRole('table', { name: 'Saved queries' });
    expect(within(table).getByText('Big orders')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: 'Open Big orders' }));
    expect(onOpen).toHaveBeenCalledWith('SELECT * FROM orders WHERE total > 10', 'Big orders.sql', ID);
    await userEvent.click(screen.getByRole('button', { name: 'Delete Big orders' }));
    await userEvent.click(within(screen.getByRole('dialog', { name: 'Delete this saved query?' })).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('No saved queries yet. Use Save to server in the editor toolbar.')).toBeInTheDocument();
    expect(deleted).toEqual([ID]);
  });

  it('saves a new query, or replaces the one the tab came from', async () => {
    const calls = [];
    server.use(
      http.post('/api/saved-queries', async ({ request }) => {
        const body = await request.json();
        calls.push(['POST', body]);
        if (body.name === 'Taken') return HttpResponse.json({ error: { code: 'NAME_TAKEN', message: 'x' } }, { status: 409 });
        return HttpResponse.json({ id: ID, name: body.name, updatedAt: '2026-10-21T08:00:00Z' }, { status: 201 });
      }),
      http.put(`/api/saved-queries/${ID}`, async ({ request }) => {
        calls.push(['PUT', await request.json()]);
        return HttpResponse.json({ id: ID, name: 'Big orders', updatedAt: '2026-10-21T08:00:00Z' });
      }),
    );
    const onSaved = vi.fn();
    const { rerender } = render(<SaveDialog key="a" initial={{ name: '', savedId: null }} getText={() => 'SELECT 1'} onSaved={onSaved} onClose={() => {}} />);
    const dialog = screen.getByRole('dialog', { name: 'Save to server' });
    expect(within(dialog).getByRole('button', { name: 'Save' })).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText('Name'), 'Taken');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('choose another');
    await userEvent.clear(within(dialog).getByLabelText('Name'));
    await userEvent.type(within(dialog).getByLabelText('Name'), 'Mine');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ id: ID, name: 'Mine' }));
    expect(calls.at(-1)).toEqual(['POST', { name: 'Mine', text: 'SELECT 1' }]);

    rerender(<SaveDialog key="b" initial={{ name: 'Big orders', savedId: ID }} getText={() => 'SELECT 2'} onSaved={onSaved} onClose={() => {}} />);
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(calls.at(-1)).toEqual(['PUT', { name: 'Big orders', text: 'SELECT 2' }]);
    await userEvent.click(screen.getByRole('checkbox', { name: /Save as a new query/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(calls.at(-1)[0]).toBe('POST');
  });
});
