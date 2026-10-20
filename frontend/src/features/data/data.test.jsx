import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse, server } from '../../mocks/server.js';
import { axeViolations } from '../../test/a11y.js';
import { DataPanel } from './DataPanel.jsx';

const SID = 'AbCdEfGhIjKlMnOpQrStUv';

function relation(extra = {}) {
  return {
    schema: 'shop',
    name: 'items',
    kind: 'table',
    accessible: true,
    owner: 'ada',
    columns: [
      { name: 'id', type: 'integer', nullable: false },
      { name: 'name', type: 'text', nullable: true },
      { name: 'total', type: 'numeric', nullable: true, generated: 's' },
    ],
    indexes: [],
    constraints: [],
    triggers: [],
    grants: [],
    myPrivileges: ['SELECT', 'INSERT', 'UPDATE', 'DELETE'],
    primaryKey: ['id'],
    loadedAt: '2026-10-20T08:00:00Z',
    ...extra,
  };
}

function rowsDoc(rows) {
  return { kind: 'rows', executionId: '00000000-0000-4000-8000-000000000001', statementClass: 'select', transaction: 'IDLE', results: [{ columns: [{ name: 'id' }, { name: 'name' }, { name: 'total' }], rows, rowCount: rows.length }] };
}

function setup({ rel = relation(), rows = [['1', 'Ada', '5'], ['2', null, '7']], script } = {}) {
  const calls = { execute: [], script: [], transaction: [] };
  server.use(
    http.get(`/api/metadata/${SID}/relation`, () => HttpResponse.json(rel)),
    http.post(`/api/db-sessions/${SID}/execute`, async ({ request }) => {
      calls.execute.push((await request.json()).sql);
      return HttpResponse.json(rowsDoc(rows));
    }),
    http.post(`/api/db-sessions/${SID}/script`, async ({ request }) => {
      const body = await request.json();
      calls.script.push(body);
      return script ? script(body) : HttpResponse.json({ kind: 'script', statements: body.sql.split(';\n').filter((s) => s.trim()).map((s, i) => ({ index: i, kind: 'command', results: [{ rowCount: i === 0 ? null : 1 }] })) });
    }),
    http.post(`/api/db-sessions/${SID}/transaction`, async ({ request }) => {
      calls.transaction.push((await request.json()).action);
      return HttpResponse.json({ kind: 'command', transaction: 'IDLE', results: [{}] });
    }),
  );
  return calls;
}

describe('table data (admin Phase 6)', () => {
  it('reads the first page by primary key and opens the query in the editor', async () => {
    const calls = setup();
    const onOpenSql = vi.fn();
    const { container } = render(<DataPanel sessionId={SID} schema="shop" table="items" transaction="IDLE" onOpenSql={onOpenSql} />);
    expect(await screen.findByText('Ada')).toBeInTheDocument();
    expect(screen.getByText('NULL')).toBeInTheDocument();
    expect(calls.execute).toEqual(['SELECT id, name, total\nFROM shop.items\nORDER BY id\nLIMIT 101 OFFSET 0']);
    expect(screen.getByRole('button', { name: 'Next 100' })).toBeDisabled();
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: 'Open query in editor' }));
    expect(onOpenSql).toHaveBeenCalledWith('SELECT id, name, total\nFROM shop.items\nORDER BY id\nLIMIT 101 OFFSET 0;\n');
  });

  it('edits, deletes and adds rows, shows every statement first, and commits when each hit its row', async () => {
    const calls = setup();
    render(<DataPanel sessionId={SID} schema="shop" table="items" transaction="IDLE" onOpenSql={() => {}} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Edit row 1' }));
    const form = screen.getByRole('group', { name: 'Row with id = 1' });
    expect(within(form).getByLabelText(/^total/)).toBeDisabled(); // generated
    await userEvent.clear(within(form).getByLabelText(/^name/));
    await userEvent.type(within(form).getByLabelText(/^name/), "Ada's");
    await userEvent.click(within(form).getByRole('button', { name: 'Keep this change' }));
    await userEvent.click(screen.getByRole('button', { name: 'Delete row 2' }));
    await userEvent.click(screen.getByRole('button', { name: 'Add row' }));
    const add = screen.getByRole('group', { name: 'New row' });
    await userEvent.click(within(add).getAllByRole('checkbox', { name: 'Default' })[0]);
    await userEvent.type(within(add).getByLabelText(/^id/), '3');
    await userEvent.click(within(add).getAllByRole('checkbox', { name: 'NULL' })[0]);
    await userEvent.click(within(add).getByRole('button', { name: 'Keep this change' }));
    expect(screen.getByText('3 unsaved changes')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Review and save' }));
    const dialog = screen.getByRole('dialog', { name: 'Save these changes?' });
    expect(within(dialog).getByLabelText('Statements to run').textContent).toBe(
      "DELETE FROM shop.items WHERE id = '2';\nUPDATE shop.items SET name = 'Ada''s'\nWHERE id = '1' AND name::text IS NOT DISTINCT FROM 'Ada';\nINSERT INTO shop.items (id, name)\nVALUES ('3', NULL);",
    );
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Saved: 1 changed, 1 added, 1 deleted.');
    expect(calls.script).toHaveLength(1);
    expect(calls.script[0].confirmed).toBe(true);
    expect(calls.script[0].sql.startsWith('BEGIN;\nDELETE FROM shop.items')).toBe(true);
    expect(calls.transaction).toEqual(['commit']);
    expect(calls.execute).toHaveLength(2); // read again after saving
  });

  it('rolls back when a row was changed by someone else, and when PostgreSQL refuses', async () => {
    let mode = 'missed';
    const calls = setup({
      script: (body) => {
        const n = body.sql.split(';\n').filter((s) => s.trim()).length;
        const statements = Array.from({ length: n }, (_, i) => ({ index: i, kind: 'command', results: [{ rowCount: i === 0 ? null : 0 }] }));
        if (mode === 'error') {
          statements[1] = { index: 1, kind: 'error', error: { sqlstate: '23505', message: 'duplicate key value violates unique constraint' } };
          return HttpResponse.json({ kind: 'script', stopReason: 'error', stoppedAt: 1, statements });
        }
        return HttpResponse.json({ kind: 'script', statements });
      },
    });
    render(<DataPanel sessionId={SID} schema="shop" table="items" transaction="IDLE" onOpenSql={() => {}} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Delete row 1' }));
    await userEvent.click(screen.getByRole('button', { name: 'Review and save' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Nothing was saved: the row with id = 1 was changed or deleted by someone else');
    expect(calls.transaction).toEqual(['rollback']);
    mode = 'error';
    await userEvent.click(screen.getByRole('button', { name: 'Review and save' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('PostgreSQL refused it: duplicate key value');
    expect(calls.transaction).toEqual(['rollback', 'rollback']);
  });

  it('rolls back when the save request itself fails', async () => {
    const calls = setup({ script: () => HttpResponse.json({ error: { code: 'EXECUTION_DISABLED', message: 'Running SQL is switched off by the administrator' } }, { status: 403 }) });
    render(<DataPanel sessionId={SID} schema="shop" table="items" transaction="IDLE" onOpenSql={() => {}} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Delete row 1' }));
    await userEvent.click(screen.getByRole('button', { name: 'Review and save' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Nothing was saved: Running SQL is switched off');
    expect(calls.transaction).toEqual(['rollback']);
  });

  it('offers no changes without a primary key, privileges, or while a transaction is open', async () => {
    setup({ rel: relation({ primaryKey: [] }) });
    const { unmount } = render(<DataPanel sessionId={SID} schema="shop" table="items" transaction="IDLE" onOpenSql={() => {}} />);
    expect(await screen.findByRole('note')).toHaveTextContent('no primary key');
    expect(screen.queryByRole('button', { name: /^Edit row/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Add row' })).toBeNull();
    unmount();

    setup({ rel: relation({ myPrivileges: ['SELECT'] }) });
    const second = render(<DataPanel sessionId={SID} schema="shop" table="items" transaction="IDLE" onOpenSql={() => {}} />);
    await screen.findByText('Ada');
    expect(screen.queryByRole('button', { name: /^Edit row|^Delete row/ })).toBeNull();
    second.unmount();

    setup();
    render(<DataPanel sessionId={SID} schema="shop" table="items" transaction="OPEN" onOpenSql={() => {}} />);
    expect(await screen.findByRole('note')).toHaveTextContent('Finish the open transaction first');
  });

  it('pages, undoes a delete, discards changes and reports nothing to save', async () => {
    const many = Array.from({ length: 101 }, (_, i) => [String(i + 1), `n${i + 1}`, '1']);
    const calls = setup({ rows: many });
    render(<DataPanel sessionId={SID} schema="shop" table="items" transaction="IDLE" onOpenSql={() => {}} />);
    await screen.findByText('n1');
    expect(screen.queryByText('n101')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Next 100' }));
    await screen.findByText('n1');
    expect(calls.execute[1]).toContain('OFFSET 100');
    await userEvent.click(screen.getByRole('button', { name: 'Previous 100' }));
    await screen.findByText('n1');
    await userEvent.click(screen.getByRole('button', { name: 'Delete row 1' }));
    expect(screen.getByRole('button', { name: 'Next 100' })).toBeDisabled(); // unsaved changes
    await userEvent.click(screen.getByRole('button', { name: 'Keep row 1' }));
    await userEvent.click(screen.getByRole('button', { name: 'Edit row 2' }));
    await userEvent.click(within(screen.getByRole('group')).getByRole('button', { name: 'Keep this change' }));
    await userEvent.click(screen.getByRole('button', { name: 'Review and save' }));
    expect(screen.getByRole('status')).toHaveTextContent('Nothing to save');
    await userEvent.click(screen.getByRole('button', { name: 'Discard changes' }));
    expect(screen.queryByText(/unsaved change/)).toBeNull();
  });
});
