// Keyboard shortcuts (admin Phase 13, decision A18): the editor's pgweb commands can be given other keys. Keys are
// CodeMirror key names ("Mod-Enter", "Shift-Mod-e", "F5"); Mod is Ctrl on Windows. Stored with the other display
// preferences in this browser only.

/** The commands that can be rebound, with their default keys (null: no key by default). */
export const COMMANDS = [
  { id: 'run', label: 'Run (selection or statement at the cursor)', default: 'Mod-Enter' },
  { id: 'runScript', label: 'Run script', default: 'Shift-Mod-Enter' },
  { id: 'explain', label: 'Explain', default: null },
  { id: 'explainAnalyze', label: 'Explain analyze', default: null },
  { id: 'toggleComment', label: 'Comment or uncomment lines', default: 'Mod-/' },
];

/** Keys that stay with the browser and the editor's basic editing, so they can't be taken. */
const RESERVED = new Set(['Mod-c', 'Mod-v', 'Mod-x', 'Mod-z', 'Mod-y', 'Mod-a', 'Mod-f', 'Mod-s', 'Mod-w', 'Mod-t', 'Mod-n', 'Mod-r', 'Mod-l', 'Mod-p',
  'Shift-Mod-z', 'Mod-Tab', 'Shift-Mod-Tab', 'F5', 'Mod-F4', 'Alt-F4', 'Tab', 'Shift-Tab', 'Enter', 'Escape', 'Backspace', 'Delete']);

const MODIFIERS = ['Alt', 'Shift', 'Mod'];

/** The key name for a keydown event, or null for a bare modifier. Shift-only printable keys aren't shortcuts. */
export function keyName(e) {
  if (['Control', 'Shift', 'Alt', 'Meta'].includes(e.key)) return null;
  let key = e.key === ' ' ? 'Space' : e.key;
  if (key.length === 1) key = key.toLowerCase();
  const mods = [];
  if (e.altKey) mods.push('Alt');
  if (e.shiftKey) mods.push('Shift');
  if (e.ctrlKey || e.metaKey) mods.push('Mod');
  if (mods.length === 0 && !/^F\d{1,2}$/.test(key)) return null;
  if (mods.length === 1 && mods[0] === 'Shift' && key.length === 1) return null;
  return [...mods, key].join('-');
}

/** "Shift-Mod-Enter" as people read it: "Ctrl+Shift+Enter". */
export function describeKey(key) {
  if (!key) return 'none';
  const parts = key.split('-');
  const last = parts.pop() || '-';
  const mods = MODIFIERS.filter((m) => parts.includes(m)).map((m) => (m === 'Mod' ? 'Ctrl' : m));
  const order = ['Ctrl', 'Alt', 'Shift'];
  mods.sort((a, b) => order.indexOf(a) - order.indexOf(b));
  return [...mods, last.length === 1 ? last.toUpperCase() : last].join('+');
}

/** The effective keys: saved choices over the defaults; invalid saved values fall back to the default. */
export function effectiveShortcuts(saved = {}) {
  const out = {};
  for (const c of COMMANDS) {
    const v = saved?.[c.id];
    out[c.id] = v === null ? null : typeof v === 'string' && v.length <= 40 && !RESERVED.has(v) ? v : c.default;
  }
  return out;
}

/** Why a key can't be used for a command, or null. */
export function problemWith(key, command, current) {
  if (key === null) return null;
  if (RESERVED.has(key)) return `${describeKey(key)} is kept for the browser or basic editing.`;
  const other = COMMANDS.find((c) => c.id !== command && current[c.id] === key);
  return other ? `${describeKey(key)} is already used for “${other.label}”.` : null;
}
