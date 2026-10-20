import { PostgreSQL, sql } from '@codemirror/lang-sql';
import { EditorSelection, EditorState } from '@codemirror/state';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createRef } from 'react';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import SqlEditor, { cspNonce } from './SqlEditor.jsx';
import { statementRanges, textToRun } from './statementAt.js';

const NONCE = 'dGVzdC1ub25jZQ==';

beforeAll(() => {
  // As the server writes it into index.html; CodeMirror's injected styles must carry it (strict CSP).
  const meta = document.createElement('meta');
  meta.setAttribute('name', 'csp-nonce');
  meta.setAttribute('content', NONCE);
  document.head.appendChild(meta);
});

function state(doc, from, to = from) {
  return EditorState.create({ doc, selection: EditorSelection.single(from, to), extensions: [sql({ dialect: PostgreSQL })] });
}

describe('textToRun', () => {
  const script = 'SELECT 1;\nSELECT $$a;b$$ AS x;\n\nUPDATE t SET a = 1 WHERE b = 2;\n';

  it('runs the statement at the cursor', () => {
    expect(textToRun(state(script, 3)).text).toBe('SELECT 1;');
    const second = textToRun(state(script, script.indexOf('$$') + 1));
    expect(second.text).toBe('SELECT $$a;b$$ AS x;');
    expect(second.from).toBe(script.indexOf('SELECT $$'));
    expect(textToRun(state(script, script.length)).text).toBe('UPDATE t SET a = 1 WHERE b = 2;');
  });

  it('runs exactly the selection when there is one', () => {
    const from = script.indexOf('UPDATE');
    expect(textToRun(state(script, from, from + 6))).toEqual({ text: 'UPDATE', from });
  });

  it('falls back to the whole text when the grammar finds no statement', () => {
    expect(textToRun(state('', 0))).toEqual({ text: '', from: 0 });
    expect(textToRun(state('   ', 1)).text).toBe('   ');
  });

  it('ignores empty statements', () => {
    expect(statementRanges(state(';;SELECT 1;;', 0))).toHaveLength(1);
  });
});

describe('SqlEditor', () => {
  it('reads the CSP nonce and puts it on injected styles', () => {
    expect(cspNonce()).toBe(NONCE);
    expect(cspNonce({ querySelector: () => null })).toBe('');
    render(<SqlEditor initialDoc="SELECT 1" onRun={() => {}} />);
    const styles = [...document.querySelectorAll('style')];
    if (styles.length > 0) {
      for (const s of styles) expect(s.getAttribute('nonce')).toBe(NONCE);
    } else {
      expect(document.adoptedStyleSheets?.length ?? 0).toBeGreaterThan(0); // constructable sheets need no nonce
    }
  });

  it('runs the statement at the cursor with Ctrl+Enter', async () => {
    const onRun = vi.fn();
    render(<SqlEditor initialDoc={'SELECT 1;\nSELECT 2;'} onRun={onRun} />);
    const content = screen.getByRole('textbox', { name: 'SQL editor' });
    content.focus();
    await userEvent.keyboard('{Control>}{Enter}{/Control}');
    expect(onRun).toHaveBeenCalledWith('SELECT 1;', 0);
  });

  it('uses the keys chosen in Settings, at once (admin Phase 13)', async () => {
    const onRun = vi.fn();
    const onCommand = vi.fn();
    const { rerender } = render(<SqlEditor initialDoc="SELECT 1;" onRun={onRun} onCommand={onCommand} shortcuts={{ explain: 'F8' }} />);
    screen.getByRole('textbox', { name: 'SQL editor' }).focus();
    await userEvent.keyboard('{F8}');
    expect(onCommand).toHaveBeenCalledWith('explain');
    await userEvent.keyboard('{Control>}{Shift>}{Enter}{/Shift}{/Control}');
    expect(onCommand).toHaveBeenCalledWith('runScript');
    rerender(<SqlEditor initialDoc="SELECT 1;" onRun={onRun} onCommand={onCommand} shortcuts={{ run: 'F9', explain: null }} />);
    screen.getByRole('textbox', { name: 'SQL editor' }).focus();
    await userEvent.keyboard('{F9}');
    expect(onRun).toHaveBeenCalledTimes(1);
    onCommand.mockClear();
    await userEvent.keyboard('{F8}');
    expect(onCommand).not.toHaveBeenCalled();
    rerender(<SqlEditor initialDoc="SELECT 1;" onRun={onRun} onCommand={onCommand} canRun={false} shortcuts={{ explain: 'F8' }} />);
    await userEvent.keyboard('{F8}');
    expect(onCommand).not.toHaveBeenCalled(); // not while running isn't allowed
  });

  it('does not run when running is not allowed, and the controllerRef drives it', () => {
    const onRun = vi.fn();
    const controllerRef = createRef();
    const { rerender } = render(<SqlEditor initialDoc="SELECT 1;" onRun={onRun} canRun={false} controllerRef={controllerRef} />);
    act(() => controllerRef.current.run());
    expect(onRun).not.toHaveBeenCalled();
    rerender(<SqlEditor initialDoc="SELECT 1;" onRun={onRun} canRun controllerRef={controllerRef} />);
    act(() => controllerRef.current.run());
    expect(onRun).toHaveBeenCalledWith('SELECT 1;', 0);
  });

  it('shows an error position by selecting the word there', () => {
    const controllerRef = createRef();
    render(<SqlEditor initialDoc={'SELECT 1;\nSELECT * FROM nope;'} onRun={() => {}} controllerRef={controllerRef} />);
    // The second statement starts at 10; PostgreSQL's position 15 in it is the "n" of "nope".
    act(() => controllerRef.current.showPosition(10, 15));
    expect(controllerRef.current.selection()).toEqual({ from: 24, to: 28 });
    act(() => controllerRef.current.showPosition(0, 9999)); // past the end: clamped to the last word
    expect(controllerRef.current.selection().to).toBe(29);
    act(() => controllerRef.current.showPosition(9, 1)); // between words: a cursor
    expect(controllerRef.current.selection()).toEqual({ from: 9, to: 9 });
  });
});
