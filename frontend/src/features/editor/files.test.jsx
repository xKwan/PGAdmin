import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useEffect, useRef } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { axeViolations } from '../../test/a11y.js';
import EditorArea from './EditorArea.jsx';
import {
  downloadText, formatBytes, hasFilePicker, isPickerCancel, MAX_FILE_BYTES, openWithPicker, readSqlFile, saveAsWithPicker, SqlFileError,
  sqlFileName, writeToHandle,
} from './sqlFiles.js';
import useEditorTabs from './useEditorTabs.js';

function file(content, name = 'q.sql') {
  return new File([content], name, { type: 'text/plain' });
}

/** A writable file handle as Edge's File System Access API gives it, recording what was written. */
function handle(name, text = '') {
  const writes = [];
  return {
    name,
    writes,
    getFile: async () => file(text, name),
    createWritable: async () => ({
      write: async (blob) => writes.push(await blob.text()),
      close: async () => {},
    }),
  };
}

function abort() {
  const e = new Error('The user aborted a request.');
  e.name = 'AbortError';
  return e;
}

describe('sqlFiles', () => {
  it('reads UTF-8 text and drops a byte-order mark', async () => {
    expect(await readSqlFile(file('SELECT \'é\';'))).toEqual({ name: 'q.sql', text: 'SELECT \'é\';' });
    expect((await readSqlFile(file('﻿SELECT 1'))).text).toBe('SELECT 1');
  });

  it('refuses files that are too large, not UTF-8, or binary', async () => {
    await expect(readSqlFile(file('x'.repeat(20)), 10)).rejects.toMatchObject({ reason: 'too_large' });
    await expect(readSqlFile(new File([new Uint8Array([0xc3, 0x28])], 'latin.sql'))).rejects.toMatchObject({ reason: 'encoding' });
    await expect(readSqlFile(file('SELECT 1\u0000'))).rejects.toMatchObject({ reason: 'binary' });
    expect(new SqlFileError('x', 'm')).toBeInstanceOf(Error);
    expect(MAX_FILE_BYTES).toBe(5 * 1024 * 1024);
  });

  it('names, sizes and picker helpers', () => {
    expect(sqlFileName('Query 1')).toBe('Query 1.sql');
    expect(sqlFileName('a/b:c*?.sql')).toBe('a_b_c__.sql');
    expect(sqlFileName('notes.TXT')).toBe('notes.TXT');
    expect(sqlFileName('   ')).toBe('query.sql');
    expect(formatBytes(10)).toBe('10 bytes');
    expect(formatBytes(2048)).toBe('2 KB');
    expect(formatBytes(3 * 1024 * 1024)).toBe('3.0 MB');
    expect(hasFilePicker({})).toBe(false);
    expect(hasFilePicker({ showOpenFilePicker: () => {}, showSaveFilePicker: () => {} })).toBe(true);
    expect(isPickerCancel(abort())).toBe(true);
    expect(isPickerCancel(new Error('x'))).toBe(false);
  });

  it('opens and saves through the File System Access API', async () => {
    const h = handle('report.sql', 'SELECT 42');
    const win = { showOpenFilePicker: vi.fn(async () => [h]), showSaveFilePicker: vi.fn(async () => h) };
    expect(await openWithPicker(win)).toEqual({ name: 'report.sql', text: 'SELECT 42', handle: h });
    await writeToHandle(h, 'SELECT 1');
    expect(await saveAsWithPicker('x.sql', 'SELECT 2', win)).toEqual({ name: 'report.sql', handle: h });
    expect(h.writes).toEqual(['SELECT 1', 'SELECT 2']);
    expect(win.showSaveFilePicker).toHaveBeenCalledWith(expect.objectContaining({ suggestedName: 'x.sql' }));
  });

  it('downloads when the browser has no file picker', () => {
    const create = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x');
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    downloadText('q.sql', 'SELECT 1');
    expect(create).toHaveBeenCalled();
    expect(click).toHaveBeenCalled();
    expect(document.querySelector('a[download]')).toBeNull(); // removed again
    create.mockRestore();
    click.mockRestore();
  });
});

function Harness({ win = {}, onRun = () => {}, canRun = true, onState = null }) {
  const tabs = useEditorTabs();
  const controllers = useRef({});
  useEffect(() => onState?.({ tabs, controllers }));
  return <EditorArea tabs={tabs} controllers={controllers} canRun={canRun} onRun={onRun} win={win} />;
}

async function editor() {
  return screen.findByRole('textbox', { name: 'SQL editor' });
}

describe('editor tabs', () => {
  it('opens new tabs, keeps each text, and marks unsaved changes', async () => {
    const { container } = render(<Harness />);
    await userEvent.click(await editor());
    await userEvent.keyboard('SELECT 1');
    expect(screen.getByRole('button', { name: /^Query 1.*unsaved$/ })).toHaveAttribute('aria-current', 'true');
    await userEvent.click(screen.getByRole('button', { name: 'New tab' }));
    expect(screen.getByRole('button', { name: 'Query 2' })).toHaveAttribute('aria-current', 'true');
    await waitFor(async () => expect(await editor()).not.toHaveTextContent('SELECT 1'));
    await userEvent.click(screen.getByRole('button', { name: /^Query 1/ }));
    expect(await editor()).toHaveTextContent('SELECT 1');
    expect(await axeViolations(container)).toEqual([]);
  });

  it('asks before closing a tab with unsaved changes', async () => {
    render(<Harness />);
    await userEvent.click(await editor());
    await userEvent.keyboard('SELECT 1');
    await userEvent.click(screen.getByRole('button', { name: 'Close Query 1' }));
    expect(screen.getByRole('dialog', { name: 'Close without saving?' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(await editor()).toHaveTextContent('SELECT 1');
    await userEvent.click(screen.getByRole('button', { name: 'Close Query 1' }));
    await userEvent.click(screen.getByRole('button', { name: 'Close and discard changes' }));
    // The last tab is replaced by a fresh one.
    expect(await screen.findByRole('button', { name: 'Query 2' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Query 1/ })).toBeNull();
    // A tab without changes closes at once.
    await userEvent.click(screen.getByRole('button', { name: 'New tab' }));
    await userEvent.click(screen.getByRole('button', { name: 'Close Query 3' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByRole('button', { name: 'Query 2' })).toHaveAttribute('aria-current', 'true');
  });

  it('opens a file into a new tab without running it (upload fallback)', async () => {
    const onRun = vi.fn();
    render(<Harness onRun={onRun} />);
    await editor();
    const input = document.querySelector('input[type="file"]');
    const click = vi.spyOn(input, 'click');
    await userEvent.click(screen.getByRole('button', { name: 'Open file…' }));
    expect(click).toHaveBeenCalled();
    await userEvent.upload(input, file('DELETE FROM t;', 'cleanup.sql'));
    expect(await screen.findByRole('button', { name: 'cleanup.sql' })).toHaveAttribute('aria-current', 'true');
    expect(screen.getByText('Opened cleanup.sql. Nothing was run.')).toBeInTheDocument();
    expect(await editor()).toHaveTextContent('DELETE FROM t;');
    expect(onRun).not.toHaveBeenCalled();
    await userEvent.upload(input, new File([new Uint8Array([0xff, 0xfe, 0x00])], 'utf16.sql'));
    expect(await screen.findByRole('alert')).toHaveTextContent('utf16.sql is not UTF-8 text');
  });

  it('opens and saves in place with the file picker; Save as asks for a new file', async () => {
    const h = handle('report.sql', 'SELECT 42');
    const other = handle('copy.sql');
    const win = { showOpenFilePicker: vi.fn(async () => [h]), showSaveFilePicker: vi.fn(async () => other) };
    render(<Harness win={win} />);
    await editor();
    await userEvent.click(screen.getByRole('button', { name: 'Open file…' }));
    expect(await screen.findByRole('button', { name: 'report.sql' })).toBeInTheDocument();
    await userEvent.click(await editor());
    await userEvent.keyboard('{End};');
    expect(screen.getByRole('button', { name: /^report.sql.*unsaved$/ })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(h.writes).toEqual(['SELECT 42;']));
    expect(screen.getByRole('button', { name: 'report.sql' })).toBeInTheDocument();
    expect(screen.getByText('Saved report.sql.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Save as…' }));
    await waitFor(() => expect(other.writes).toEqual(['SELECT 42;']));
    expect(screen.getByRole('button', { name: 'copy.sql' })).toBeInTheDocument();
    // Closing the picker is not an error.
    win.showOpenFilePicker.mockRejectedValueOnce(abort());
    await userEvent.click(screen.getByRole('button', { name: 'Open file…' }));
    expect(screen.queryByRole('alert')).toBeNull();
    win.showSaveFilePicker.mockRejectedValueOnce(new Error('Disk full'));
    await userEvent.click(screen.getByRole('button', { name: 'Save as…' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Disk full');
  });

  it('a new tab without a file is saved with the picker, or downloaded without one', async () => {
    const h = handle('mine.sql');
    const win = { showOpenFilePicker: vi.fn(), showSaveFilePicker: vi.fn(async () => h) };
    const { unmount } = render(<Harness win={win} />);
    await userEvent.click(await editor());
    await userEvent.keyboard('SELECT 7');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(h.writes).toEqual(['SELECT 7']));
    expect(win.showSaveFilePicker).toHaveBeenCalledWith(expect.objectContaining({ suggestedName: 'Query 1.sql' }));
    unmount();
    const create = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x');
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    render(<Harness />);
    await userEvent.click(await editor());
    await userEvent.keyboard('SELECT 8');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(screen.getByText('Downloaded Query 1.sql. Your browser decides where downloads go.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Query 1.sql' })).toBeInTheDocument();
    create.mockRestore();
    click.mockRestore();
  });

  it('Ctrl+Enter in a tab runs from that tab; controllers follow the tabs', async () => {
    const onRun = vi.fn();
    const expose = { current: null };
    render(<Harness onRun={onRun} onState={(st) => {
      expose.current = st;
    }} />);
    await userEvent.click(await editor());
    await userEvent.keyboard('SELECT 5{Control>}{Enter}{/Control}');
    expect(onRun).toHaveBeenCalledWith('t1', 'SELECT 5', 0);
    expect(Object.keys(expose.current.controllers.current)).toEqual(['t1']);
    await userEvent.click(screen.getByRole('button', { name: 'New tab' }));
    await waitFor(() => expect(Object.keys(expose.current.controllers.current)).toEqual(['t1', 't2']));
    expect(expose.current.controllers.current.t2.script()).toEqual({ text: '', from: 0 });
    act(() => expose.current.tabs.close('t1'));
    await waitFor(() => expect(Object.keys(expose.current.controllers.current)).toEqual(['t2']));
    act(() => expose.current.tabs.activate('nope')); // unknown tab: ignored
    expect(expose.current.tabs.active.id).toBe('t2');
    act(() => expose.current.tabs.close('nope'));
    expect(expose.current.tabs.tabs).toHaveLength(1);
  });
});
