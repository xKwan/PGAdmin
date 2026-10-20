import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import SettingsDialog from './SettingsDialog.jsx';
import { COMMANDS, describeKey, effectiveShortcuts, keyName, problemWith } from './shortcuts.js';
import usePreferences from './usePreferences.js';

function memoryStorage() {
  const m = new Map();
  return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), map: m };
}

describe('keyboard shortcuts (admin Phase 13)', () => {
  it('names keys as CodeMirror does and as people read them', () => {
    expect(keyName({ key: 'Enter', ctrlKey: true })).toBe('Mod-Enter');
    expect(keyName({ key: 'E', ctrlKey: true, shiftKey: true })).toBe('Shift-Mod-e');
    expect(keyName({ key: 'F8' })).toBe('F8');
    expect(keyName({ key: ' ', altKey: true })).toBe('Alt-Space');
    expect(keyName({ key: 'x' })).toBeNull(); // a plain letter is typing, not a shortcut
    expect(keyName({ key: 'X', shiftKey: true })).toBeNull();
    expect(keyName({ key: 'Control', ctrlKey: true })).toBeNull();
    expect(describeKey('Shift-Mod-e')).toBe('Ctrl+Shift+E');
    expect(describeKey('Alt-F8')).toBe('Alt+F8');
    expect(describeKey(null)).toBe('none');
  });

  it('falls back to the defaults for missing or invalid saved keys, and refuses reserved or taken ones', () => {
    const defaults = effectiveShortcuts({});
    expect(defaults).toEqual(Object.fromEntries(COMMANDS.map((c) => [c.id, c.default])));
    expect(effectiveShortcuts({ run: 'Mod-c', explain: 'F8', toggleComment: null, runScript: 42 })).toMatchObject({
      run: 'Mod-Enter', explain: 'F8', toggleComment: null, runScript: 'Shift-Mod-Enter',
    });
    expect(effectiveShortcuts(null).run).toBe('Mod-Enter');
    expect(problemWith('Mod-v', 'run', defaults)).toContain('kept for the browser');
    expect(problemWith('Shift-Mod-Enter', 'run', defaults)).toContain('Run script');
    expect(problemWith('F8', 'explain', defaults)).toBeNull();
    expect(problemWith(null, 'explain', defaults)).toBeNull();
  });

  it('records a new key in Settings, refuses a taken one, clears and restores', async () => {
    const s = memoryStorage();
    function Harness() {
      const { prefs, change } = usePreferences(s);
      return <SettingsDialog open prefs={prefs} onChange={change} onClose={() => {}} />;
    }
    render(<Harness />);
    expect(screen.getByRole('row', { name: /Explain analyze/ })).toHaveTextContent('none');
    await userEvent.click(screen.getByRole('button', { name: 'Change keys for Explain' }));
    const recorder = screen.getByRole('textbox', { name: 'New keys for Explain' });
    fireEvent.keyDown(recorder, { key: 'Enter', ctrlKey: true }); // taken by Run
    expect(screen.getByRole('alert')).toHaveTextContent('already used');
    fireEvent.keyDown(recorder, { key: 'F8' });
    expect(screen.getByRole('row', { name: /^Explain F8/ })).toBeInTheDocument();
    expect(JSON.parse(s.map.get('pgweb.preferences.v1')).shortcuts).toEqual({ explain: 'F8' });
    await userEvent.click(screen.getByRole('button', { name: 'No keys for Run (selection or statement at the cursor)' }));
    expect(JSON.parse(s.map.get('pgweb.preferences.v1')).shortcuts.run).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Change keys for Run script' }));
    fireEvent.keyDown(screen.getByRole('textbox', { name: 'New keys for Run script' }), { key: 'Escape' });
    expect(screen.queryByRole('textbox')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Restore the default keys' }));
    expect(JSON.parse(s.map.get('pgweb.preferences.v1')).shortcuts).toEqual({});
    expect(screen.getByRole('row', { name: /^Run \(selection/ })).toHaveTextContent('Ctrl+Enter');
  });
});
