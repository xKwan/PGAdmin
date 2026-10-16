import { ensureSyntaxTree } from '@codemirror/language';

/**
 * What Ctrl+Enter runs: the selection if there is one, otherwise the statement around the cursor.
 *
 * Statement boundaries here come from the editor's own SQL grammar (lezer, PostgreSQL dialect) and are only a
 * convenience for choosing text. They are never trusted: the server parses the text with PostgreSQL's parser and
 * refuses anything that isn't exactly one statement (ADR-0009).
 *
 * @returns {{text: string, from: number}} the text and its offset in the document (for error positions)
 */
export function textToRun(state) {
  const sel = state.selection.main;
  if (!sel.empty) {
    return { text: state.sliceDoc(sel.from, sel.to), from: sel.from };
  }
  const statements = statementRanges(state);
  if (statements.length === 0) {
    return { text: state.doc.toString(), from: 0 };
  }
  const pos = sel.head;
  const around = statements.find((s) => s.from <= pos && pos <= s.to) || statements.findLast((s) => s.to <= pos) || statements[0];
  return { text: state.sliceDoc(around.from, around.to), from: around.from };
}

/** Non-blank Statement nodes of the document, in order. */
export function statementRanges(state) {
  const tree = ensureSyntaxTree(state, state.doc.length, 1000);
  const out = [];
  if (!tree) return out;
  for (let node = tree.topNode.firstChild; node; node = node.nextSibling) {
    if (node.name !== 'Statement') continue;
    // A lone ";" is a Statement node too; skip it.
    if (state.sliceDoc(node.from, node.to).replace(/;\s*$/, '').trim() === '') continue;
    out.push({ from: node.from, to: node.to });
  }
  return out;
}
