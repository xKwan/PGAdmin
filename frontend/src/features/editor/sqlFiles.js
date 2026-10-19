// Local .sql files (admin Phase 2). Files never go to the server: they are read into the editor and written back
// from it, in the browser. Opening a file never runs it. Edge's File System Access API (showOpenFilePicker,
// showSaveFilePicker) is used when present, so Save writes back to the same file; otherwise an <input type="file">
// opens and a download saves.

/** Largest file the editor opens (the server runs at most limits.maxSqlBytes, 1 MiB by default, per request). */
export const MAX_FILE_BYTES = 5 * 1024 * 1024;

const ACCEPT = { 'text/plain': ['.sql', '.txt'] };
const TYPES = [{ description: 'SQL files', accept: ACCEPT }];

/** A file that can't be opened, with a reason the user can act on. */
export class SqlFileError extends Error {
  constructor(reason, message) {
    super(message);
    this.name = 'SqlFileError';
    this.reason = reason;
  }
}

/** True when the user closed a file picker without choosing: not an error. */
export function isPickerCancel(error) {
  return error?.name === 'AbortError';
}

/**
 * Reads a file as UTF-8 text: at most MAX_FILE_BYTES, valid UTF-8 (a byte-order mark is dropped), no NUL characters
 * (PostgreSQL text can't hold them and the server refuses them).
 * @returns {Promise<{name: string, text: string}>}
 */
export async function readSqlFile(file, maxBytes = MAX_FILE_BYTES) {
  if (file.size > maxBytes) {
    throw new SqlFileError('too_large', `${file.name} is ${formatBytes(file.size)}; files up to ${formatBytes(maxBytes)} can be opened.`);
  }
  const bytes = new Uint8Array(await file.arrayBuffer());
  let text;
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    throw new SqlFileError('encoding', `${file.name} is not UTF-8 text. Save it as UTF-8 and open it again.`);
  }
  if (text.includes('\u0000')) {
    throw new SqlFileError('binary', `${file.name} contains NUL characters; it doesn't look like a SQL text file.`);
  }
  return { name: file.name, text: text.replace(/^\uFEFF/, '') };
}

export function formatBytes(n) {
  if (n < 1024) return `${n} bytes`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

/** Whether this browser can open and save files in place (Edge and Chrome can). */
export function hasFilePicker(win = window) {
  return typeof win.showOpenFilePicker === 'function' && typeof win.showSaveFilePicker === 'function';
}

/** Lets the user pick a .sql file and reads it. Resolves with {name, text, handle}. */
export async function openWithPicker(win = window) {
  const [handle] = await win.showOpenFilePicker({ types: TYPES, multiple: false, excludeAcceptAllOption: false });
  const file = await handle.getFile();
  return { ...(await readSqlFile(file)), handle };
}

/** Writes the text (UTF-8) to a file the user chose before. */
export async function writeToHandle(handle, text) {
  const writable = await handle.createWritable();
  try {
    await writable.write(new Blob([text], { type: 'text/plain;charset=utf-8' }));
  } finally {
    await writable.close();
  }
}

/** Lets the user choose where to save, then writes. Resolves with {name, handle}. */
export async function saveAsWithPicker(suggestedName, text, win = window) {
  const handle = await win.showSaveFilePicker({ suggestedName, types: TYPES });
  await writeToHandle(handle, text);
  return { name: handle.name, handle };
}

/** Saves by downloading (browsers without the File System Access API). */
export function downloadText(name, text, doc = document) {
  const url = URL.createObjectURL(new Blob([text], { type: 'application/sql;charset=utf-8' }));
  const a = doc.createElement('a');
  a.href = url;
  a.download = name;
  a.rel = 'noopener';
  doc.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

/** A safe file name for a tab title: Windows-reserved characters replaced, ending in .sql. */
export function sqlFileName(title) {
  const base = (title || 'query').replace(/[<>:"/\\|?*]/g, '_').trim() || 'query';
  return /\.(sql|txt)$/i.test(base) ? base : `${base}.sql`;
}
