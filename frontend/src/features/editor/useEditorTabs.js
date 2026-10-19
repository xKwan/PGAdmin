import { useCallback, useRef, useState } from 'react';

function nextTab(counter, doc = '', title = null, handle = null) {
  counter.current += 1;
  const n = counter.current;
  return { id: `t${n}`, title: title || `Query ${n}`, handle, dirty: false, initialDoc: doc };
}

/**
 * Editor tabs (admin Phase 2): each tab is one CodeMirror document with a title, the file it came from (a File
 * System Access handle, or none) and whether it has unsaved changes. Tabs live in the workspace, so they survive
 * switching database, disconnecting and reconnecting. Nothing is stored in the browser.
 */
export default function useEditorTabs(initialDoc = '') {
  const counter = useRef(1);
  const [state, setState] = useState(() => ({
    active: 't1',
    tabs: [{ id: 't1', title: 'Query 1', handle: null, dirty: false, initialDoc }],
  }));

  /** Opens a new tab (empty, or with a file's text) and makes it active. Returns its id. */
  const open = useCallback((doc = '', title = null, handle = null) => {
    const tab = nextTab(counter, doc, title, handle);
    setState((s) => ({ active: tab.id, tabs: [...s.tabs, tab] }));
    return tab.id;
  }, []);

  /** Closes a tab (the caller asked about unsaved changes); the last tab is replaced by an empty one. */
  const close = useCallback((id) => {
    const replacement = nextTab(counter);
    setState((s) => {
      const i = s.tabs.findIndex((t) => t.id === id);
      if (i < 0) return s;
      const tabs = s.tabs.filter((t) => t.id !== id);
      if (tabs.length === 0) return { active: replacement.id, tabs: [replacement] };
      const active = s.active === id ? tabs[Math.min(i, tabs.length - 1)].id : s.active;
      return { active, tabs };
    });
  }, []);

  const activate = useCallback((id) => setState((s) => (s.tabs.some((t) => t.id === id) ? { ...s, active: id } : s)), []);

  /** The text changed since the last open or save. */
  const markDirty = useCallback((id) => {
    setState((s) => (s.tabs.find((t) => t.id === id)?.dirty ? s : { ...s, tabs: s.tabs.map((t) => (t.id === id ? { ...t, dirty: true } : t)) }));
  }, []);

  /** Saved: optionally under a new title and file handle. */
  const markSaved = useCallback((id, { title, handle } = {}) => {
    setState((s) => ({
      ...s,
      tabs: s.tabs.map((t) => (t.id === id ? { ...t, dirty: false, ...(title ? { title } : {}), ...(handle ? { handle } : {}) } : t)),
    }));
  }, []);

  const activeTab = state.tabs.find((t) => t.id === state.active) || state.tabs[0];
  return {
    tabs: state.tabs,
    active: activeTab,
    anyDirty: state.tabs.some((t) => t.dirty),
    open,
    close,
    activate,
    markDirty,
    markSaved,
  };
}
