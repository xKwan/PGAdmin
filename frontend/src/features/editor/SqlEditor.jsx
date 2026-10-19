import { autocompletion, closeBrackets, closeBracketsKeymap, completionKeymap } from '@codemirror/autocomplete';
import { defaultKeymap, history, historyKeymap, indentWithTab, toggleComment } from '@codemirror/commands';
import { PostgreSQL, sql } from '@codemirror/lang-sql';
import { bracketMatching, defaultHighlightStyle, indentOnInput, syntaxHighlighting } from '@codemirror/language';
import { highlightSelectionMatches, searchKeymap } from '@codemirror/search';
import { EditorSelection, EditorState } from '@codemirror/state';
import { drawSelection, EditorView, highlightActiveLine, keymap, lineNumbers, placeholder } from '@codemirror/view';
import { useEffect, useRef } from 'react';
import { textToRun } from './statementAt.js';

/** The page's CSP nonce (set by the server in a meta tag): CodeMirror's injected styles must carry it. */
export function cspNonce(doc = document) {
  return doc.querySelector('meta[name="csp-nonce"]')?.getAttribute('content') || '';
}

/**
 * CodeMirror 6 SQL editor (plan Phase 8): PostgreSQL highlighting, bracket matching, search (Ctrl+F), comment
 * toggle (Ctrl+/), keyword completion, and Ctrl+Enter to run the selection or the statement at the cursor.
 *
 * `controllerRef` receives {run(), showPosition(from, position), focus(), selection(), insert(text)} so the page can
 * drive the editor.
 */
export default function SqlEditor({ initialDoc = '', onRun, canRun = true, controllerRef, label = 'SQL editor' }) {
  const host = useRef(null);
  const view = useRef(null);
  const runRef = useRef(onRun);
  const canRunRef = useRef(canRun);
  useEffect(() => {
    runRef.current = onRun;
    canRunRef.current = canRun;
  });

  useEffect(() => {
    const runCommand = (v) => {
      if (canRunRef.current) {
        const { text, from } = textToRun(v.state);
        runRef.current?.(text, from);
      }
    };
    // A CodeMirror command returns true when it handled the key (even if running isn't allowed right now).
    const runKey = (v) => {
      runCommand(v);
      return true;
    };
    const v = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: initialDoc,
        extensions: [
          EditorView.cspNonce.of(cspNonce()),
          lineNumbers(),
          history(),
          drawSelection(),
          indentOnInput(),
          bracketMatching(),
          closeBrackets(),
          autocompletion(),
          highlightActiveLine(),
          highlightSelectionMatches(),
          syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
          sql({ dialect: PostgreSQL, upperCaseKeywords: true }),
          placeholder('Type SQL, then press Ctrl+Enter to run the statement at the cursor'),
          keymap.of([
            { key: 'Mod-Enter', run: runKey, preventDefault: true },
            { key: 'Mod-/', run: toggleComment },
            ...closeBracketsKeymap,
            ...defaultKeymap,
            ...searchKeymap,
            ...historyKeymap,
            ...completionKeymap,
            indentWithTab,
          ]),
          EditorView.contentAttributes.of({ 'aria-label': label }),
        ],
      }),
    });
    view.current = v;
    if (controllerRef) {
      controllerRef.current = {
        run: () => runCommand(v),
        focus: () => v.focus(),
        /** The whole text (kept when the workspace switches to another database). */
        text: () => v.state.doc.toString(),
        selection: () => ({ from: v.state.selection.main.from, to: v.state.selection.main.to }),
        /** Replaces the selection (or inserts at the cursor), for example a quoted name from the object browser. */
        insert: (text) => {
          v.dispatch(v.state.replaceSelection(text));
          v.focus();
        },
        /** Selects the character at `position` (1-based, in the text that was run, which started at `from`). */
        showPosition: (from, position) => {
          const at = Math.min(v.state.doc.length, from + Math.max(0, position - 1));
          const word = v.state.wordAt(at);
          v.dispatch({ selection: word ? EditorSelection.range(word.from, word.to) : EditorSelection.cursor(at), scrollIntoView: true });
          v.focus();
        },
      };
    }
    return () => {
      v.destroy();
      view.current = null;
    };
    // The editor is created once; later prop changes go through refs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return <div className="sql-editor" ref={host} />;
}
