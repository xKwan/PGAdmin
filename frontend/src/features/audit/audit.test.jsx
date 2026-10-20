import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse, server } from '../../mocks/server.js';
import { axeViolations } from '../../test/a11y.js';
import LandingPage from '../landing/LandingPage.jsx';
import AuditPage, { localValue } from './AuditPage.jsx';

function event(seq, extra = {}) {
  return { seq, occurredAt: '2026-10-20T08:00:00Z', type: 'exec_outcome', windowsUser: 'CORP\\ada', database: 'sales', statementClass: 'select', sqlNormalised: 'SELECT * FROM t WHERE id = $1', outcome: 'success', ...extra };
}

describe('audit viewer (admin Phase 9)', () => {
  it('searches, shows details and pages to older events', async () => {
    const bodies = [];
    server.use(
      http.post('/api/audit/search', async ({ request }) => {
        const body = await request.json();
        bodies.push(body);
        if (body.before) return HttpResponse.json({ events: [event(1, { type: 'app_login', sqlNormalised: undefined })], limit: 100, more: false });
        return HttpResponse.json({ events: [event(3, { details: '{"reasons": ["drop"]}' }), event(2)], limit: 100, more: true });
      }),
    );
    const onBack = vi.fn();
    const { container } = render(<AuditPage onBack={onBack} />);
    await userEvent.type(screen.getByLabelText('User (account or SID)'), 'ada');
    await userEvent.selectOptions(screen.getByLabelText('Outcome'), 'success');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    const table = await screen.findByRole('table', { name: 'Events, newest first' });
    expect(within(table).getAllByText('SELECT * FROM t WHERE id = $1')).toHaveLength(2);
    expect(bodies[0]).toMatchObject({ user: 'ada', outcome: 'success', type: '', limit: 100 });
    expect(new Date(bodies[0].to) - new Date(bodies[0].from)).toBeGreaterThan(23 * 3600 * 1000);
    expect(await axeViolations(container)).toEqual([]);

    await userEvent.click(screen.getByRole('button', { name: 'Details of event 3' }));
    const details = screen.getByRole('region', { name: 'Event 3' });
    expect(details).toHaveTextContent('{"reasons": ["drop"]}');
    expect(details).toHaveTextContent('CORP\\ada');

    await userEvent.click(screen.getByRole('button', { name: 'Older events' }));
    expect(await within(table).findByText('app_login')).toBeInTheDocument();
    expect(bodies[1]).toMatchObject({ before: 2, user: 'ada' });
    expect(screen.queryByRole('button', { name: 'Older events' })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: '← Servers' }));
    expect(onBack).toHaveBeenCalled();
  });

  it('explains when your role is not an auditor, and when nothing matches', async () => {
    let denied = true;
    server.use(
      http.post('/api/audit/search', () =>
        denied
          ? HttpResponse.json({ error: { code: 'AUDIT_READ_DENIED', message: 'x' } }, { status: 403 })
          : HttpResponse.json({ events: [], limit: 100, more: false }),
      ),
    );
    render(<AuditPage onBack={() => {}} />);
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('pgweb_audit_reader');
    denied = false;
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByText('No events match.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('is offered on the start page only when the server has it switched on', async () => {
    const me = (auditViewer) => ({ user: { account: 'ada', dataAdmin: false }, capabilities: { internal: false, external: true, executionEnabled: true, auditViewer } });
    const onOpen = vi.fn();
    const { rerender } = render(<LandingPage me={me(false)} onOpen={onOpen} />);
    expect(screen.queryByRole('button', { name: /Audit trail/ })).toBeNull();
    rerender(<LandingPage me={me(true)} onOpen={onOpen} />);
    await userEvent.click(screen.getByRole('button', { name: /Audit trail/ }));
    expect(onOpen).toHaveBeenCalledWith('audit');
  });

  it('formats local times for the date fields', () => {
    expect(localValue(new Date(2026, 0, 2, 3, 4))).toBe('2026-01-02T03:04');
  });
});
