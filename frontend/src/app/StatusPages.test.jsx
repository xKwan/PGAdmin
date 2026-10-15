import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { axeViolations } from '../test/a11y.js';
import { Forbidden, SignInFailed, Unavailable } from './StatusPages.jsx';

describe('status pages', () => {
  it('sign-in failed explains each cause', async () => {
    const { container, rerender } = render(<SignInFailed error={{ code: 'SESSION_EXPIRED' }} />);
    expect(screen.getByText(/Your session has ended/)).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    rerender(<SignInFailed error={null} />);
    expect(screen.getByText(/did not sign you in automatically/)).toBeInTheDocument();
    const assign = vi.fn();
    const original = window.location;
    Object.defineProperty(window, 'location', { configurable: true, value: { ...original, assign, pathname: '/p' } });
    try {
      await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
      expect(assign).toHaveBeenCalledWith('/p');
    } finally {
      Object.defineProperty(window, 'location', { configurable: true, value: original });
    }
  });

  it('forbidden shows the server message or a default', () => {
    const { rerender } = render(<Forbidden error={{ message: 'Not for you' }} />);
    expect(screen.getByText('Not for you')).toBeInTheDocument();
    rerender(<Forbidden />);
    expect(screen.getByText('You are not allowed to use this function.')).toBeInTheDocument();
  });

  it('unavailable distinguishes audit from other outages', async () => {
    const retry = vi.fn();
    const { rerender } = render(<Unavailable error={{ code: 'UNAVAILABLE', message: 'Down for maintenance' }} onRetry={retry} />);
    expect(screen.getByRole('heading', { name: 'The service is unavailable' })).toBeInTheDocument();
    expect(screen.getByText('Down for maintenance')).toBeInTheDocument();
    rerender(<Unavailable error={null} onRetry={retry} />);
    expect(screen.getByText('The server could not be reached.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(retry).toHaveBeenCalledOnce();
  });
});
