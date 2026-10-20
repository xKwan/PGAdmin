import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { axeViolations } from '../../test/a11y.js';
import { applyTheme, readPreferences, themeOf, writePreferences } from './preferences.js';
import SettingsDialog from './SettingsDialog.jsx';
import usePreferences from './usePreferences.js';

function memoryStorage(initial = {}) {
  const m = new Map(Object.entries(initial));
  return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), map: m };
}

const blocked = {
  getItem: () => {
    throw new Error('SecurityError');
  },
  setItem: () => {
    throw new Error('QuotaExceededError');
  },
};

afterEach(() => document.documentElement.removeAttribute('data-theme'));

describe('settings: colour theme (admin Phase 12)', () => {
  it('reads and writes preferences, tolerating missing, broken or blocked storage', () => {
    const s = memoryStorage();
    expect(readPreferences(s)).toEqual({});
    expect(writePreferences({ theme: 'dark' }, s)).toBe(true);
    expect(readPreferences(s)).toEqual({ theme: 'dark' });
    expect(readPreferences(memoryStorage({ 'pgweb.preferences.v1': '{not json' }))).toEqual({});
    expect(readPreferences(memoryStorage({ 'pgweb.preferences.v1': '[1,2]' }))).toEqual({});
    expect(readPreferences(blocked)).toEqual({});
    expect(writePreferences({ theme: 'dark' }, blocked)).toBe(false);
    expect(readPreferences(null)).toEqual({});
    expect(themeOf({ theme: 'neon' })).toBe('system');
    expect(themeOf(null)).toBe('system');
  });

  it('puts the theme on the page, or leaves it to the system', () => {
    applyTheme('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    applyTheme('system');
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false);
    applyTheme('light', null); // no document: nothing happens
  });

  it('switches the theme at once and keeps it', async () => {
    const s = memoryStorage();
    const onClose = vi.fn();
    function Harness() {
      const { prefs, change, notSaved } = usePreferences(s);
      return <SettingsDialog open prefs={prefs} onChange={change} notSaved={notSaved} onClose={onClose} />;
    }
    const { container } = render(<Harness />);
    expect(screen.getByRole('radio', { name: 'Same as Windows' })).toBeChecked();
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(screen.getByRole('radio', { name: 'Dark' }));
    expect(screen.getByRole('radio', { name: 'Dark' })).toBeChecked();
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(JSON.parse(s.map.get('pgweb.preferences.v1'))).toEqual({ theme: 'dark' });
    await userEvent.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalled();
  });

  it('says when the browser does not keep settings', async () => {
    function Harness() {
      const { prefs, change, notSaved } = usePreferences(blocked);
      return <SettingsDialog open prefs={prefs} onChange={change} notSaved={notSaved} onClose={() => {}} />;
    }
    render(<Harness />);
    await userEvent.click(screen.getByRole('radio', { name: 'Light' }));
    expect(screen.getByRole('status')).toHaveTextContent('last until the page is closed');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });

  it('renders nothing while closed', () => {
    const { container } = render(<SettingsDialog open={false} prefs={{}} onChange={() => {}} onClose={() => {}} />);
    expect(container).toBeEmptyDOMElement();
  });
});
