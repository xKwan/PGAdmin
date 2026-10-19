import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { envelope, http, HttpResponse, server } from '../mocks/server.js';
import Workspace from './Workspace.jsx';

const SESSION = {
  id: 'AbCdEfGhIjKlMnOpQrStUv',
  profile: 'external',
  database: 'sales',
  role: 'alice',
  serverVersion: '18.6',
  backendPid: 4242,
  companion: true,
  state: 'OPEN',
  transaction: 'IDLE',
};

async function connect(executionEnabled = true) {
  server.use(
    http.post('/api/profiles/external/databases', () => HttpResponse.json({ databases: [{ name: 'sales', owner: 'alice', owned: true }] })),
    http.post('/api/db-sessions/external', () => HttpResponse.json(SESSION, { status: 201 })),
  );
  render(<Workspace profile="external" executionEnabled={executionEnabled} onBack={() => {}} />);
  await userEvent.click(await screen.findByRole('button', { name: /sales/ })); // listed as soon as the workspace opens
  await screen.findByRole('textbox', { name: 'SQL editor' });
}

describe('Workspace execution', () => {
  it('runs from the Run button and shows a refusal with its position', async () => {
    const sent = [];
    server.use(
      http.post(`/api/db-sessions/${SESSION.id}/execute`, async ({ request }) => {
        sent.push((await request.json()).sql);
        return HttpResponse.json(
          { error: { code: 'SYNTAX_ERROR', message: 'Syntax error: syntax error at end of input', position: 1, requestId: 'r' } },
          { status: 400 },
        );
      }),
    );
    await connect();
    expect(screen.queryByText('Running SQL is switched off by the administrator')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Run' }));
    expect(await screen.findByText('Not run')).toBeInTheDocument();
    expect(sent).toEqual(['']);
    await userEvent.click(screen.getByRole('button', { name: 'Show position 1 in the editor' }));
    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: 'SQL editor' }));
  });

  it('shows results and a session the server closed', async () => {
    let call = 0;
    server.use(
      http.post(`/api/db-sessions/${SESSION.id}/execute`, () => {
        call += 1;
        if (call === 1) {
          return HttpResponse.json({
            executionId: 'e1', statementClass: 'select', kind: 'rows', transaction: 'IDLE', durationMs: 3, retrievedAt: '2026-10-16T08:00:00Z',
            results: [{ kind: 'rows', columns: [{ name: 'answer', type: 'int4' }], rows: [['42']], rowCount: 1, truncated: false }],
          });
        }
        return HttpResponse.json({
          executionId: 'e2', statementClass: 'select', kind: 'timeout', cancelOutcome: 'escalated_terminated', sessionEnded: true,
          transaction: 'IDLE', durationMs: 60000, retrievedAt: '2026-10-16T08:01:00Z', results: [],
        });
      }),
    );
    await connect();
    const editor = screen.getByRole('textbox', { name: 'SQL editor' });
    await userEvent.click(editor);
    await userEvent.keyboard('SELECT 42');
    expect(editor).toHaveTextContent('SELECT 42');
    await userEvent.click(screen.getByRole('button', { name: 'Run' }));
    expect(await screen.findByText('42', { selector: 'td' })).toBeInTheDocument();
    await userEvent.click(within(screen.getByRole('region', { name: 'Results' })).getByRole('button', { name: 'Refresh' }));
    expect(await screen.findByText('Stopped: the time limit was reached')).toBeInTheDocument();
    expect(screen.getByText(/closed by the server. Anything not committed was rolled back/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reconnect to sales' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Choose another database' }));
    expect(await screen.findByText('No database open')).toBeInTheDocument();
  });

  it('an ended session reported as 410 also asks to connect again', async () => {
    server.use(http.post(`/api/db-sessions/${SESSION.id}/execute`, () => envelope(410, 'SESSION_LOST', 'The database session has ended (lost).')));
    await connect();
    await userEvent.click(screen.getByRole('button', { name: 'Run' }));
    expect(await screen.findByText(/closed by the server. Anything not committed was rolled back/)).toBeInTheDocument();
    expect(screen.getByText('The database session has ended. Connect again.')).toBeInTheDocument();
  });

  it('cannot run when execution is switched off', async () => {
    await connect(false);
    expect(screen.getByRole('button', { name: 'Run' })).toBeDisabled();
  });
});
