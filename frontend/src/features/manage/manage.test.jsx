import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { axeViolations } from '../../test/a11y.js';
import { ACTIONS, ManagePanel, toInput } from './ManagePanel.jsx';

function sql() {
  return screen.getByLabelText('Generated SQL').textContent;
}

describe('management screens (admin Phase 5)', () => {
  it('generates SQL from a form and opens it in the editor, without running anything', async () => {
    const onOpen = vi.fn();
    const { container } = render(<ManagePanel database="sales" onOpen={onOpen} />);
    expect(screen.getByRole('status')).toHaveTextContent('Name is required.');
    expect(screen.getByRole('button', { name: 'Open in editor' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Name'), 'Reports');
    await userEvent.type(screen.getByLabelText('Owner (optional)'), 'ada');
    expect(sql()).toBe('CREATE SCHEMA "Reports" AUTHORIZATION ada;\n');
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: 'Open in editor' }));
    expect(onOpen).toHaveBeenCalledWith('CREATE SCHEMA "Reports" AUTHORIZATION ada;\n', 'create-schema.sql');
  });

  it('builds a table from the column editor', async () => {
    render(<ManagePanel database="sales" onOpen={() => {}} />);
    await userEvent.selectOptions(screen.getByLabelText('What to do'), 'create-table');
    await userEvent.type(screen.getByLabelText('Schema'), 'shop');
    await userEvent.type(screen.getByLabelText('Name'), 'items');
    await userEvent.type(screen.getByLabelText('Column 1 name'), 'id');
    await userEvent.type(screen.getByLabelText('Column 1 type'), 'bigint');
    await userEvent.click(screen.getAllByRole('checkbox', { name: 'Primary key' })[0]);
    await userEvent.click(screen.getByRole('button', { name: 'Add column' }));
    await userEvent.type(screen.getByLabelText('Column 2 name'), 'label');
    await userEvent.type(screen.getByLabelText('Column 2 type'), 'text');
    await userEvent.type(screen.getByLabelText('Column 2 default'), "'x'");
    await userEvent.click(screen.getAllByRole('checkbox', { name: 'NOT NULL' })[1]);
    expect(sql()).toBe("CREATE TABLE shop.items (\n    id bigint,\n    label text NOT NULL DEFAULT 'x',\n    PRIMARY KEY (id)\n);\n");
    await userEvent.click(screen.getByRole('button', { name: 'Remove column 2' }));
    expect(sql()).toBe('CREATE TABLE shop.items (\n    id bigint,\n    PRIMARY KEY (id)\n);\n');
    expect(screen.getByRole('button', { name: 'Remove column 1' })).toBeDisabled();
  });

  it('warns before destructive actions and refuses the connected database', async () => {
    render(<ManagePanel database="sales" onOpen={() => {}} />);
    await userEvent.selectOptions(screen.getByLabelText('What to do'), 'drop-database');
    await userEvent.type(screen.getByLabelText('Database'), 'sales');
    expect(screen.getByRole('status')).toHaveTextContent('connected to');
    await userEvent.clear(screen.getByLabelText('Database'));
    await userEvent.type(screen.getByLabelText('Database'), 'old_sales');
    expect(sql()).toBe('DROP DATABASE old_sales;\n');
    expect(screen.getByRole('note')).toHaveTextContent('pgweb asks you to confirm');
  });

  it('handles selects, three-way choices, lists and privileges', async () => {
    render(<ManagePanel database="sales" onOpen={() => {}} />);
    await userEvent.selectOptions(screen.getByLabelText('What to do'), 'alter-role');
    await userEvent.type(screen.getByLabelText('Role'), 'app');
    expect(screen.getByRole('status')).toHaveTextContent('Choose at least one change.');
    await userEvent.selectOptions(screen.getByLabelText('Can log in'), 'off');
    await userEvent.selectOptions(screen.getByLabelText('Can create databases'), 'on');
    expect(sql()).toBe('ALTER ROLE app NOLOGIN CREATEDB;\n');

    await userEvent.selectOptions(screen.getByLabelText('What to do'), 'grant-table');
    await userEvent.type(screen.getByLabelText('Schema'), 's');
    await userEvent.type(screen.getByLabelText('Name'), 't');
    await userEvent.click(screen.getByRole('checkbox', { name: 'SELECT' }));
    await userEvent.click(screen.getByRole('checkbox', { name: 'UPDATE' }));
    await userEvent.click(screen.getByRole('checkbox', { name: 'SELECT' }));
    await userEvent.type(screen.getByLabelText('To role (or PUBLIC)'), 'readers');
    expect(sql()).toBe('GRANT UPDATE ON TABLE s.t TO readers;\n');

    await userEvent.selectOptions(screen.getByLabelText('What to do'), 'add-constraint');
    await userEvent.type(screen.getByLabelText('Schema'), 's');
    await userEvent.type(screen.getByLabelText('Table'), 't');
    await userEvent.selectOptions(screen.getByLabelText('Kind'), 'unique');
    await userEvent.type(screen.getByLabelText('Columns'), 'a, b');
    expect(sql()).toBe('ALTER TABLE s.t ADD UNIQUE (a, b);\n');

    await userEvent.selectOptions(screen.getByLabelText('What to do'), 'create-role');
    await userEvent.type(screen.getByLabelText('Name'), 'r');
    await userEvent.click(screen.getByRole('checkbox', { name: 'Does not inherit privileges (NOINHERIT)' }));
    expect(sql()).toBe('CREATE ROLE r NOINHERIT;\n');
  });

  it('maps every action\'s form to its generator without crashing on an empty form', () => {
    for (const a of ACTIONS) {
      const input = toInput(a, {}, 'sales');
      expect(() => a.build(input), a.id).toThrow(); // every action needs at least a name
    }
    const add = ACTIONS.find((a) => a.id === 'add-column');
    expect(toInput(add, { schema: 's', table: 't', 'column.name': 'c', 'column.type': 'int', 'column.notNull': true }, 'db')).toEqual({
      current: 'db',
      schema: 's',
      table: 't',
      column: { name: 'c', type: 'int', notNull: true, default: undefined },
    });
  });
});
