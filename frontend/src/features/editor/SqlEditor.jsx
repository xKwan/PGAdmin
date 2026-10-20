import { autocompletion, closeBrackets, closeBracketsKeymap, completionKeymap } from '@codemirror/autocomplete';
import { defaultKeymap, history, historyKeymap, indentWithTab, toggleComment } from '@codemirror/commands';
import { keywordCompletionSource, PostgreSQL, sql } from '@codemirror/lang-sql';
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
 * `completionSource` (admin Phase 10) adds names from the catalog to the keywords; it may change between renders.
 *
 * `controllerRef` receives {run(), script(), statement(), showPosition(from, position), select(from, to), focus(), text(), selection(),
 * insert(text)} so the page can drive the editor. `onChange` is called whenever the text changes (unsaved changes).
 */
export default function SqlEditor({ initialDoc = '', onRun, onChange, canRun = true, controllerRef, label = 'SQL editor', completionSource = null }) {
  const host = useRef(null);
  const view = useRef(null);
  const runRef = useRef(onRun);
  const changeRef = useRef(onChange);
  const canRunRef = useRef(canRun);
  const completionRef = useRef(completionSource);
  useEffect(() => {
    runRef.current = onRun;
    changeRef.current = onChange;
    canRunRef.current = canRun;
    completionRef.current = completionSource;
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
          autocompletion({
            override: [keywordCompletionSource(PostgreSQL, true), (context) => completionRef.current?.(context) ?? null],
          }),
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
          EditorView.updateListener.of((u) => {
            if (u.docChanged) changeRef.current?.();
          }),
        ],
      }),
    });
    view.current = v;
    if (controllerRef) {
      controllerRef.current = {
        run: () => runCommand(v),
        /** What Run script sends: the selection if there is one, otherwise the whole text (never just one statement). */
        script: () => {
          const sel = v.state.selection.main;
          return sel.empty ? { text: v.state.doc.toString(), from: 0 } : { text: v.state.sliceDoc(sel.from, sel.to), from: sel.from };
        },
        /** Selects a range (for example a script statement) and scrolls to it. */
        select: (from, to) => {
          const len = v.state.doc.length;
          v.dispatch({ selection: EditorSelection.range(Math.min(from, len), Math.min(to, len)), scrollIntoView: true });
          v.focus();
        },
        focus: () => v.focus(),
        /** The whole text (kept when the workspace switches to another database). */
        text: () => v.state.doc.toString(),
        selection: () => ({ from: v.state.selection.main.from, to: v.state.selection.main.to }),
        /** What Run would send, without running it: {text, from} (for Explain). */
        statement: () => textToRun(v.state),
        /** Replaces the selection (or inserts at the cursor), for example a quoted name from the object browser. */
        insert: (text) => {
          v.dispatch(v.state.replaceSelection(text));
          v.focus();
        },
        /** Selects the character at `position` (1-based, in the text that was run, which started at `from`). */
        showPosition: (from, position) => {
          // `from` can be before the text (Explain adds a prefix): such positions land on the text's start.
          const at = Math.max(0, Math.min(v.state.doc.length, from + Math.max(0, position - 1)));
          const word = v.state.wordAt(at);
          v.dispatch({ selection: word ? EditorSelection.range(word.from, word.to) : EditorSelection.cursor(at), scrollIntoView: true });
          v.focus();
        },
      };
    }
    return () => {
      v.destroy();
      view.current = null;
      if (controllerRef) controllerRef.current = null;
    };
    // The editor is created once; later prop changes go through refs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return <div className="sql-editor" ref={host} />;
}
