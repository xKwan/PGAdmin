import { lazy, Suspense, useRef, useState } from 'react';
import { ConfirmDialog, Spinner } from '../../components/common/index.jsx';
import {
  downloadText, hasFilePicker, isPickerCancel, openWithPicker, readSqlFile, saveAsWithPicker, sqlFileName, writeToHandle,
} from './sqlFiles.js';

// The editor (CodeMirror) is its own chunk (bundle budget, plan Phase 8).
const SqlEditor = lazy(() => import('./SqlEditor.jsx'));

/**
 * Editor tabs with local file handling (admin Phase 2). `tabs` comes from useEditorTabs (owned by the workspace, so
 * tabs survive switching database). Every tab keeps its own editor mounted (hidden when inactive), so undo history
 * and cursor stay. `controllers.current[tabId]` is that tab's editor controller. Opening a file never runs it; files
 * are read and written in the browser only.
 */
export default function EditorArea({ tabs, controllers, canRun, onRun, completionSource = null, shortcuts = null, onCommand = null, win = window }) {
  const [closing, setClosing] = useState(null);
  const [notice, setNotice] = useState(null);
  const fileInput = useRef(null);
  const picker = hasFilePicker(win);

  const opened = ({ name, text, handle = null }) => {
    tabs.open(text, name, handle);
    setNotice({ tone: 'info', text: `Opened ${name}. Nothing was run.` });
  };
  const failed = (error) => {
    if (!isPickerCancel(error)) setNotice({ tone: 'error', text: error.message || 'The file could not be opened or saved.' });
  };

  const openFile = async () => {
    setNotice(null);
    if (!picker) {
      fileInput.current?.click();
      return;
    }
    try {
      opened(await openWithPicker(win));
    } catch (e) {
      failed(e);
    }
  };

  const fileChosen = async (event) => {
    const file = event.target.files?.[0];
    event.target.value = ''; // the same file can be chosen again later
    if (!file) return;
    try {
      opened(await readSqlFile(file));
    } catch (e) {
      failed(e);
    }
  };

  const save = async (as = false) => {
    const tab = tabs.active;
    const text = controllers.current[tab.id]?.text?.() ?? '';
    setNotice(null);
    try {
      if (!as && tab.handle) {
        await writeToHandle(tab.handle, text);
        tabs.markSaved(tab.id);
        setNotice({ tone: 'info', text: `Saved ${tab.title}.` });
      } else if (picker) {
        const { name, handle } = await saveAsWithPicker(sqlFileName(tab.title), text, win);
        tabs.markSaved(tab.id, { title: name, handle });
        setNotice({ tone: 'info', text: `Saved ${name}.` });
      } else {
        const name = sqlFileName(tab.title);
        downloadText(name, text);
        tabs.markSaved(tab.id, { title: name });
        setNotice({ tone: 'info', text: `Downloaded ${name}. Your browser decides where downloads go.` });
      }
    } catch (e) {
      failed(e);
    }
  };

  const askClose = (tab) => (tab.dirty ? setClosing(tab) : tabs.close(tab.id));
  const confirmClose = () => {
    tabs.close(closing.id);
    setClosing(null);
  };

  return (
    <div className="editor-area">
      <div className="editor-tabs">
        <nav aria-label="Editor tabs" className="tab-strip">
          <ul className="tab-list">
            {tabs.tabs.map((t) => (
              <li key={t.id} className={t.id === tabs.active.id ? 'tab tab-active' : 'tab'}>
                <button
                  type="button"
                  aria-current={t.id === tabs.active.id ? 'true' : undefined}
                  className="tab-button"
                  onClick={() => tabs.activate(t.id)}
                  title={t.dirty ? `${t.title} (unsaved changes)` : t.title}
                >
                  {t.title}
                  {t.dirty ? <span className="tab-dirty"> • unsaved</span> : null}
                </button>
                <button type="button" className="tab-close" aria-label={`Close ${t.title}`} onClick={() => askClose(t)}>
                  ×
                </button>
              </li>
            ))}
          </ul>
          <button type="button" className="btn btn-link" onClick={() => tabs.open()} aria-label="New tab">
            + New
          </button>
        </nav>
        <div className="file-actions">
          <button type="button" className="btn" onClick={openFile}>
            Open file…
          </button>
          <button type="button" className="btn" onClick={() => save(false)}>
            Save
          </button>
          <button type="button" className="btn" onClick={() => save(true)}>
            Save as…
          </button>
          <input ref={fileInput} type="file" accept=".sql,.txt,text/plain" className="visually-hidden" tabIndex={-1} aria-hidden="true" onChange={fileChosen} />
        </div>
      </div>
      {notice ? (
        <p className={notice.tone === 'error' ? 'file-notice file-notice-error' : 'file-notice'} role={notice.tone === 'error' ? 'alert' : 'status'}>
          {notice.text}
        </p>
      ) : null}
      <Suspense fallback={<Spinner label="Loading the editor" />}>
        {tabs.tabs.map((t) => (
          <div key={t.id} hidden={t.id !== tabs.active.id} className="tab-panel">
            <SqlEditor
              initialDoc={t.initialDoc}
              canRun={canRun}
              completionSource={completionSource}
              shortcuts={shortcuts}
              onCommand={onCommand}
              onRun={(text, from) => onRun(t.id, text, from)}
              onChange={() => tabs.markDirty(t.id)}
              controllerRef={{
                get current() {
                  return controllers.current[t.id] ?? null;
                },
                set current(c) {
                  if (c) controllers.current[t.id] = c;
                  else delete controllers.current[t.id];
                },
              }}
            />
          </div>
        ))}
      </Suspense>
      <ConfirmDialog
        open={Boolean(closing)}
        title="Close without saving?"
        confirmLabel="Close and discard changes"
        danger
        onConfirm={confirmClose}
        onCancel={() => setClosing(null)}
      >
        {closing?.title} has changes that are not saved to a file. Closing the tab discards them.
      </ConfirmDialog>
    </div>
  );
}
