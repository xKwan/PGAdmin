import { HighlightStyle } from '@codemirror/language';
import { EditorView } from '@codemirror/view';
import { tags as t } from '@lezer/highlight';

// The editor's colours (admin Phase 12): the page's colour tokens, so the light and dark themes (and the system
// setting) apply to the editor without rebuilding it. Defined in styles.css under :root.

export const highlightStyle = HighlightStyle.define([
  { tag: [t.keyword, t.modifier, t.operatorKeyword, t.controlKeyword, t.definitionKeyword], color: 'var(--syn-keyword)' },
  { tag: [t.string, t.special(t.string), t.regexp], color: 'var(--syn-string)' },
  { tag: [t.number, t.bool, t.null, t.atom], color: 'var(--syn-number)' },
  { tag: [t.lineComment, t.blockComment, t.comment], color: 'var(--syn-comment)', fontStyle: 'italic' },
  { tag: [t.typeName, t.standard(t.name)], color: 'var(--syn-type)' },
  { tag: [t.operator, t.punctuation, t.bracket], color: 'var(--syn-operator)' },
  { tag: t.special(t.name), color: 'var(--text)' },
  { tag: t.invalid, color: 'var(--syn-invalid)' },
]);

export const editorTheme = EditorView.theme({
  '&': { color: 'var(--text)', backgroundColor: 'var(--panel)' },
  '.cm-content': { caretColor: 'var(--text)' },
  '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--text)' },
  '.cm-gutters': { backgroundColor: 'var(--rownum-bg)', color: 'var(--muted)', borderRight: '1px solid var(--line)' },
  '.cm-activeLine': { backgroundColor: 'var(--cm-active-line)' },
  '.cm-activeLineGutter': { backgroundColor: 'var(--cm-active-line)' },
  '&.cm-focused > .cm-scroller > .cm-selectionLayer .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': {
    backgroundColor: 'var(--cm-selection)',
  },
  '.cm-selectionMatch': { backgroundColor: 'var(--cm-selection)' },
  '&.cm-focused .cm-matchingBracket': { backgroundColor: 'var(--cm-selection)', outline: '1px solid var(--line)' },
  '.cm-placeholder': { color: 'var(--muted)' },
  '.cm-tooltip': { backgroundColor: 'var(--panel)', color: 'var(--text)', border: '1px solid var(--line)' },
  '.cm-tooltip-autocomplete ul li[aria-selected]': { backgroundColor: 'var(--accent)', color: 'var(--accent-text)' },
  '.cm-completionDetail': { color: 'inherit', opacity: '0.75' },
  '.cm-panels': { backgroundColor: 'var(--surface-alt)', color: 'var(--text)' },
  '.cm-panels input, .cm-panels button': { color: 'var(--text)', backgroundColor: 'var(--panel)' },
  '.cm-searchMatch': { backgroundColor: 'var(--warn-bg)', outline: '1px solid var(--warn)' },
});
