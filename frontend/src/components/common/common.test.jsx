import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { axeViolations } from '../../test/a11y.js';
import { randomText, seededRandom } from '../../test/random.js';
import { ConfirmDialog, EmptyState, ErrorPanel, Spinner, StatusBadge, ToastList } from './index.jsx';
import { Splitter } from './Splitter.jsx';

describe('common components', () => {
  it('render hostile text as text, never as HTML', () => {
    const rnd = seededRandom('xss');
    const payloads = ['<img src=x onerror=alert(1)>', '<script>alert(1)</script>', '"><svg onload=alert(1)>', randomText(rnd, 40)];
    for (const p of payloads) {
      const { container, unmount } = render(
        <>
          <StatusBadge>{p}</StatusBadge>
          <EmptyState title={p}>{p}</EmptyState>
          <ErrorPanel error={{ message: p, code: p, requestId: p }} />
        </>,
      );
      expect(container.querySelector('img, script, svg')).toBeNull();
      expect(container.textContent).toContain(p);
      unmount();
    }
  });

  it('spinner and empty state are accessible', async () => {
    const { container } = render(
      <div>
        <Spinner label="Loading results" />
        <EmptyState title="Nothing here" action={<button type="button">Do it</button>} />
      </div>,
    );
    expect(screen.getByRole('status')).toHaveTextContent('Loading results');
    expect(screen.getByRole('button', { name: 'Do it' })).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('error panel shows code and request id and retries', async () => {
    const retry = vi.fn();
    render(<ErrorPanel title="Query failed" error={{ message: 'boom', code: 'X1', requestId: 'r-1' }} onRetry={retry} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Query failed');
    expect(screen.getByText('Code: X1')).toBeInTheDocument();
    expect(screen.getByText('Request ID: r-1')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(retry).toHaveBeenCalledOnce();
    render(<ErrorPanel error="plain string" />);
    expect(screen.getByText('plain string')).toBeInTheDocument();
  });

  it('confirm dialog focuses cancel, confirms, cancels and closes on Escape', async () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    const { rerender, container } = render(
      <ConfirmDialog open title="Drop table?" danger confirmLabel="Drop" onConfirm={onConfirm} onCancel={onCancel}>
        This cannot be undone.
      </ConfirmDialog>,
    );
    const dialog = screen.getByRole('dialog', { name: 'Drop table?' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await userEvent.click(screen.getByRole('button', { name: 'Drop' }));
    expect(onConfirm).toHaveBeenCalledOnce();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onCancel).toHaveBeenCalledOnce();
    expect(await axeViolations(container)).toEqual([]);
    rerender(<ConfirmDialog open={false} title="x" onConfirm={onConfirm} onCancel={onCancel} />);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('toasts are dismissible', async () => {
    const dismiss = vi.fn();
    render(<ToastList toasts={[{ id: 1, text: 'Saved', tone: 'info' }, { id: 2, text: 'Failed', tone: 'danger' }]} onDismiss={dismiss} />);
    expect(screen.getAllByRole('status')).toHaveLength(2);
    await userEvent.click(screen.getAllByRole('button', { name: 'Dismiss notification' })[1]);
    expect(dismiss).toHaveBeenCalledWith(2);
  });

  it('splitter resizes with the keyboard within its bounds', async () => {
    const rnd = seededRandom('splitter');
    const initial = 20 + Math.floor(rnd() * 40);
    render(<Splitter label="Resize panes" initial={initial} min={10} max={80} first={<p>left</p>} second={<p>right</p>} />);
    const sep = screen.getByRole('separator', { name: 'Resize panes' });
    expect(sep).toHaveAttribute('aria-valuenow', String(initial));
    sep.focus();
    await userEvent.keyboard('{ArrowRight}');
    expect(sep).toHaveAttribute('aria-valuenow', String(initial + 2));
    await userEvent.keyboard('{Shift>}{ArrowLeft}{/Shift}');
    expect(sep).toHaveAttribute('aria-valuenow', String(initial - 8));
    await userEvent.keyboard('{Home}');
    expect(sep).toHaveAttribute('aria-valuenow', '10');
    await userEvent.keyboard('{End}');
    expect(sep).toHaveAttribute('aria-valuenow', '80');
    await userEvent.keyboard('{ArrowRight}');
    expect(sep).toHaveAttribute('aria-valuenow', '80');
    await userEvent.keyboard('a');
    expect(sep).toHaveAttribute('aria-valuenow', '80');
  });

  it('vertical splitter uses up and down and follows the pointer', () => {
    const { container } = render(<Splitter direction="vertical" label="Rows" initial={50} first={<p>top</p>} second={<p>bottom</p>} />);
    const sep = screen.getByRole('separator', { name: 'Rows' });
    expect(sep).toHaveAttribute('aria-orientation', 'horizontal');
    fireEvent.keyDown(sep, { key: 'ArrowDown' });
    expect(sep).toHaveAttribute('aria-valuenow', '52');
    fireEvent.keyDown(sep, { key: 'ArrowUp' });
    expect(sep).toHaveAttribute('aria-valuenow', '50');
    const box = container.querySelector('.split');
    box.getBoundingClientRect = () => ({ left: 0, top: 0, width: 200, height: 400 });
    fireEvent.pointerDown(sep, { clientY: 200 });
    fireEvent.pointerMove(window, { clientY: 100 });
    expect(sep).toHaveAttribute('aria-valuenow', '25');
    fireEvent.pointerUp(window);
    fireEvent.pointerMove(window, { clientY: 300 });
    expect(sep).toHaveAttribute('aria-valuenow', '25');
  });
});
