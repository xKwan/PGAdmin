import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse, server } from '../../mocks/server.js';
import { axeViolations } from '../../test/a11y.js';
import { ApprovalNeeded, ApprovalsPage } from './ApprovalPanels.jsx';

const SID = 'AbCdEfGhIjKlMnOpQrStUv';
const ID = 'f1d3a5b0-0000-4000-8000-000000000001';

function req(extra = {}) {
  return {
    id: ID, state: 'pending', requester: 'CORP\\ada', mine: true, profile: 'external', database: 'sales', role: 'ada', script: false,
    reasons: ['truncate'], createdAt: '2026-10-21T08:00:00Z', expiresAt: '2026-10-21T08:30:00Z', text: 'TRUNCATE orders', ...extra,
  };
}

describe('two-person approval (admin Phase 14)', () => {
  it('asks for approval, waits, and runs the same text again once approved', async () => {
    let state = 'pending';
    const bodies = [];
    server.use(
      http.post(`/api/db-sessions/${SID}/approval`, async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json(req(), { status: 201 });
      }),
      http.get('/api/approvals', () => HttpResponse.json({ mine: [req({ state, decidedBy: state === 'approved' ? 'CORP\\bob' : undefined })], toDecide: [], approver: false })),
    );
    const onRunAgain = vi.fn();
    const { container } = render(<ApprovalNeeded sessionId={SID} need={{ text: 'TRUNCATE orders', script: false, reasons: ['truncate'] }} onRunAgain={onRunAgain} onDismiss={() => {}} />);
    const panel = screen.getByRole('region', { name: 'Approval needed' });
    expect(panel).toHaveTextContent('A second person must approve this');
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(within(panel).getByRole('button', { name: 'Request approval' }));
    expect(await within(panel).findByText('Waiting for a second person')).toBeInTheDocument();
    expect(bodies).toEqual([{ sql: 'TRUNCATE orders', script: false }]);
    await userEvent.click(within(panel).getByRole('button', { name: 'Check again' }));
    expect(within(panel).getByText('Waiting for a second person')).toBeInTheDocument();
    state = 'approved';
    await userEvent.click(within(panel).getByRole('button', { name: 'Check again' }));
    await userEvent.click(await within(panel).findByRole('button', { name: 'Run it now' }));
    expect(onRunAgain).toHaveBeenCalled();
  });

  it('withdraws a request, and reports errors', async () => {
    server.use(
      http.post(`/api/db-sessions/${SID}/approval`, () => HttpResponse.json(req(), { status: 201 })),
      http.delete(`/api/approvals/${ID}`, () => HttpResponse.json(req({ state: 'withdrawn' }))),
    );
    const onDismiss = vi.fn();
    render(<ApprovalNeeded sessionId={SID} need={{ text: 'TRUNCATE orders', script: true, reasons: ['truncate'] }} onRunAgain={() => {}} onDismiss={onDismiss} />);
    await userEvent.click(screen.getByRole('button', { name: 'Request approval' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Withdraw the request' }));
    expect(await screen.findByText('Withdrawn')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(onDismiss).toHaveBeenCalled();

    server.use(http.post(`/api/db-sessions/${SID}/approval`, () => HttpResponse.json({ error: { code: 'TOO_MANY_REQUESTS', message: 'You have 5 requests waiting.' } }, { status: 409 })));
    render(<ApprovalNeeded sessionId={SID} need={{ text: 'DROP TABLE x', script: false, reasons: ['drop'] }} onRunAgain={() => {}} onDismiss={() => {}} />);
    await userEvent.click(screen.getAllByRole('button', { name: 'Request approval' }).at(-1));
    expect(await screen.findByRole('alert')).toHaveTextContent('5 requests waiting');
  });

  it('shows approvers the text and lets them decide on others’ requests only', async () => {
    const decisions = [];
    let toDecide = [req({ mine: false, requester: 'CORP\\cyd' })];
    server.use(
      http.get('/api/approvals', () => HttpResponse.json({ mine: [req({ state: 'used' })], toDecide, approver: true })),
      http.post(`/api/approvals/${ID}/decision`, async ({ request }) => {
        decisions.push(await request.json());
        toDecide = [];
        return HttpResponse.json(req({ state: 'approved' }));
      }),
    );
    const { container } = render(<ApprovalsPage onBack={() => {}} />);
    const list = await screen.findByRole('list', { name: 'Requests to decide' });
    expect(within(list).getByLabelText('SQL of the request by CORP\\cyd')).toHaveTextContent('TRUNCATE orders');
    expect(within(screen.getByRole('list', { name: 'Your requests' })).getByText('Used')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(within(list).getByRole('button', { name: 'Approve' }));
    const dialog = screen.getByRole('dialog', { name: 'Approve this request?' });
    await userEvent.type(within(dialog).getByLabelText('Comment (optional)'), 'ok');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Approve' }));
    expect(decisions).toEqual([{ approve: true, comment: 'ok' }]);
    expect(await screen.findByText('No requests are waiting.')).toBeInTheDocument();
  });

  it('tells someone outside the group that they cannot decide', async () => {
    server.use(http.get('/api/approvals', () => HttpResponse.json({ mine: [], toDecide: [], approver: false })));
    render(<ApprovalsPage onBack={() => {}} />);
    expect(await screen.findByText(/You aren’t in the approver group/)).toBeInTheDocument();
    expect(screen.getByText('You have no requests.')).toBeInTheDocument();
  });
});
