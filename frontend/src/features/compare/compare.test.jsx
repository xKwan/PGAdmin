import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse, server } from '../../mocks/server.js';
import { axeViolations } from '../../test/a11y.js';
import { ComparePanel } from './ComparePanel.jsx';

const SID = 'AbCdEfGhIjKlMnOpQrStUv';
const EMPTY = { relations: [], columns: [], constraints: [], indexes: [], triggers: [], sequences: [], routines: [] };
const SNAPSHOTS = {
  shop: {
    ...EMPTY,
    relations: [{ name: 'items', kind: 'table' }],
    columns: [{ table: 'items', name: 'id', type: 'integer', notNull: true }, { table: 'items', name: 'label', type: 'text', notNull: false }],
  },
  shop_test: {
    ...EMPTY,
    relations: [{ name: 'items', kind: 'table' }, { name: 'old', kind: 'table' }],
    columns: [{ table: 'items', name: 'id', type: 'integer', notNull: true }],
  },
};

function handlers(calls) {
  server.use(
    http.get(`/api/metadata/${SID}/schemas`, () =>
      HttpResponse.json({ schemas: Object.keys(SNAPSHOTS).concat('huge').map((name) => ({ name, owner: 'ada', accessible: true })), loadedAt: '2026-10-20T08:00:00Z' }),
    ),
    http.get(`/api/metadata/${SID}/snapshot`, ({ request }) => {
      const schema = new URL(request.url).searchParams.get('schema');
      calls.push(schema);
      if (schema === 'huge') {
        return HttpResponse.json({ error: { code: 'SNAPSHOT_TOO_LARGE', message: 'This schema has more than 2000 tables: too large to compare here.' } }, { status: 422 });
      }
      return HttpResponse.json({ snapshot: SNAPSHOTS[schema], loadedAt: '2026-10-20T08:00:05Z', database: 'sales', schema });
    }),
  );
}

function Harness({ onOpen }) {
  const [source, setSource] = useState(null);
  return <ComparePanel sessionId={SID} database="sales" source={source} onSource={setSource} onOpen={onOpen} />;
}

describe('schema comparison panel (admin Phase 8)', () => {
  it('reads a source, compares a target and opens the SQL in the editor without running it', async () => {
    const calls = [];
    handlers(calls);
    const onOpen = vi.fn();
    const { container } = render(<Harness onOpen={onOpen} />);
    const compare = await screen.findByRole('button', { name: 'Compare with source' });
    expect(compare).toBeDisabled();
    const [sourcePick, targetPick] = screen.getAllByLabelText('Schema in sales');
    await userEvent.selectOptions(sourcePick, 'shop');
    await userEvent.click(screen.getByRole('button', { name: 'Read source' }));
    expect(await screen.findByText('sales · shop')).toBeInTheDocument();
    await userEvent.selectOptions(targetPick, 'shop_test');
    await userEvent.click(compare);
    const table = await screen.findByRole('table', { name: 'Differences' });
    expect(within(table).getByText('items.label').closest('tr')).toHaveTextContent('Only in source');
    expect(within(table).getByText('old').closest('tr')).toHaveTextContent('Only in target');
    expect(await axeViolations(container)).toEqual([]);

    await userEvent.click(screen.getByRole('button', { name: 'Open SQL in editor' }));
    const [sql, title] = onOpen.mock.calls[0];
    expect(title).toBe('compare shop to shop_test.sql');
    expect(sql).toContain('SET LOCAL search_path TO shop_test;\n');
    expect(sql).toContain('ALTER TABLE items ADD COLUMN label text;');
    expect(sql).toContain('-- DROP TABLE old;');

    await userEvent.click(screen.getByRole('checkbox', { name: /Drop what exists only in the target/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Open SQL in editor' }));
    expect(onOpen.mock.calls[1][0]).toContain('\nDROP TABLE old;\n');
    expect(calls).toEqual(['shop', 'shop_test']);
  });

  it('says when there are no differences, explains refusals, and forgets the source on Clear', async () => {
    const calls = [];
    handlers(calls);
    render(<Harness onOpen={() => {}} />);
    await screen.findByRole('button', { name: 'Compare with source' });
    const [sourcePick, targetPick] = screen.getAllByLabelText('Schema in sales');
    await userEvent.selectOptions(sourcePick, 'huge');
    await userEvent.click(screen.getByRole('button', { name: 'Read source' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('too large to compare here');
    await userEvent.selectOptions(sourcePick, 'shop');
    await userEvent.click(screen.getByRole('button', { name: 'Read source' }));
    await screen.findByText('sales · shop');
    expect(screen.queryByRole('alert')).toBeNull();
    await userEvent.selectOptions(targetPick, 'shop');
    await userEvent.click(screen.getByRole('button', { name: 'Compare with source' }));
    expect(await screen.findByText('No differences in the compared objects.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Clear' }));
    expect(screen.getByText('No source read yet.')).toBeInTheDocument();
    expect(screen.queryByText('No differences in the compared objects.')).toBeNull();
    expect(screen.getByRole('button', { name: 'Compare with source' })).toBeDisabled();
  });
});
