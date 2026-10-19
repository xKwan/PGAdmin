import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { axeViolations } from '../../test/a11y.js';
import PlanView, { misestimate, parsePlan } from './PlanView.jsx';

const ESTIMATED = [
  {
    Plan: {
      'Node Type': 'Hash Join', 'Join Type': 'Inner', 'Startup Cost': 1.5, 'Total Cost': 40.2, 'Plan Rows': 120,
      'Hash Cond': '(o.customer_id = c.id)',
      Plans: [
        { 'Node Type': 'Seq Scan', 'Relation Name': 'orders', Schema: 'shop', Alias: 'o', 'Startup Cost': 0, 'Total Cost': 20, 'Plan Rows': 1000, Filter: '(total > 10)' },
        { 'Node Type': 'Index Scan', 'Relation Name': 'customers', Schema: 'shop', Alias: 'customers', 'Index Name': 'customers_pkey', 'Startup Cost': 0.2, 'Total Cost': 8, 'Plan Rows': 1 },
      ],
    },
    'Planning Time': 0.123,
  },
];

const ANALYZED = [
  {
    Plan: {
      'Node Type': 'Seq Scan', 'Relation Name': 'orders', 'Startup Cost': 0, 'Total Cost': 20, 'Plan Rows': 5,
      'Actual Startup Time': 0.01, 'Actual Total Time': 0.5, 'Actual Rows': 500, 'Actual Loops': 1, 'Shared Hit Blocks': 4, 'Shared Read Blocks': 0,
    },
    'Planning Time': 0.1,
    'Execution Time': 0.7,
  },
];

const result = (cell, extra = {}) => ({ columns: [{ name: 'QUERY PLAN', type: 'json' }], rows: [[cell]], ...extra });

describe('EXPLAIN viewer (admin Phase 3)', () => {
  it('recognises only an EXPLAIN (FORMAT JSON) plan', () => {
    expect(parsePlan(result(JSON.stringify(ESTIMATED)))).toEqual(ESTIMATED[0]);
    expect(parsePlan(result('Seq Scan on orders  (cost=0.00..20.00 rows=1000 width=8)'))).toBeNull();
    expect(parsePlan(result(JSON.stringify(ESTIMATED), { truncatedCells: [[0, 0]] }))).toBeNull();
    expect(parsePlan(result(JSON.stringify([{ nope: 1 }])))).toBeNull();
    expect(parsePlan(result(null))).toBeNull();
    expect(parsePlan({ columns: [{ name: 'a' }, { name: 'b' }], rows: [['x', 'y']] })).toBeNull();
    expect(parsePlan(null)).toBeNull();
  });

  it('shows the estimated plan as a tree with its conditions, and the raw result on request', async () => {
    const { container } = render(<PlanView plan={ESTIMATED[0]} raw={<p>raw grid</p>} />);
    expect(screen.getByText(/Estimated plan \(EXPLAIN\): the statement was not run/)).toBeInTheDocument();
    const tree = screen.getByRole('list', { name: 'Query plan' });
    expect(within(tree).getByText('Hash Join (Inner)')).toBeInTheDocument();
    expect(within(tree).getByText('Seq Scan on shop.orders o')).toBeInTheDocument();
    expect(within(tree).getByText('Index Scan on shop.customers using customers_pkey')).toBeInTheDocument();
    expect(within(tree).getByText('(total > 10)')).toBeInTheDocument();
    expect(within(tree).getByText('cost 1.5..40.2 · 120 rows estimated')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: 'Show as table' }));
    expect(screen.getByText('raw grid')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Show as plan' }));
    expect(screen.getByRole('list', { name: 'Query plan' })).toBeInTheDocument();
  });

  it('shows actual times, buffers and a bad estimate after EXPLAIN ANALYZE', () => {
    render(<PlanView plan={ANALYZED[0]} raw={null} />);
    expect(screen.getByText(/Plan with actual times \(EXPLAIN ANALYZE\).*execution 0\.700 ms/)).toBeInTheDocument();
    expect(screen.getByText(/actual 0\.010 ms\.\.0\.500 ms · 500 rows × 1 loop/)).toBeInTheDocument();
    expect(screen.getByText('buffers: 4 hit, 0 read')).toBeInTheDocument();
    expect(screen.getByText('estimate off ×100')).toBeInTheDocument();
    expect(misestimate({ 'Plan Rows': 10, 'Actual Rows': 12 })).toBeNull();
    expect(misestimate({ 'Plan Rows': 1000, 'Actual Rows': 0 })).toBe(1000);
    expect(misestimate({ 'Plan Rows': 10 })).toBeNull();
  });
});
