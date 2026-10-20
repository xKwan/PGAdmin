import { useState } from 'react';
import { recordExport } from '../../api/execution.js';
import { toCsv, toJson } from './csv.js';

/** "20261020-091500" for file names, in local time. */
export function stamp(date = new Date()) {
  const p = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}${p(date.getMonth() + 1)}${p(date.getDate())}-${p(date.getHours())}${p(date.getMinutes())}${p(date.getSeconds())}`;
}

/** Hands a text file to the browser's download (a Blob URL in this page's own origin, released right after). */
export function download(text, fileName, type) {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

const FORMATS = {
  csv: { label: 'Export CSV', type: 'text/csv;charset=utf-8', build: toCsv },
  json: { label: 'Export JSON', type: 'application/json', build: toJson },
};

/**
 * Export of the rows on screen (admin Phase 7, decision A4): only what the browser already holds. The server records
 * the export first (format and counts, never the data); without that record nothing is saved.
 */
export function ExportButtons({ sessionId, result }) {
  const [problem, setProblem] = useState(null);
  const [busy, setBusy] = useState(false);
  const first = result.results[0];
  const run = async (format) => {
    setProblem(null);
    setBusy(true);
    try {
      await recordExport(sessionId, { executionId: result.executionId, format, rows: first.rows.length, columns: first.columns.length });
      const f = FORMATS[format];
      download(f.build(first.columns, first.rows), `result-${stamp()}.${format}`, f.type);
    } catch (e) {
      setProblem(e?.code === 'AUDIT_UNAVAILABLE' ? 'Not exported: the audit database is unavailable, so the export could not be recorded.' : `Not exported: ${e?.message || 'the request failed.'}`);
    } finally {
      setBusy(false);
    }
  };
  return (
    <span className="export-buttons">
      {Object.entries(FORMATS).map(([format, f]) => (
        <button key={format} type="button" className="btn" disabled={busy} onClick={() => run(format)} title={`Save the ${first.rows.length} rows shown as a file`}>
          {f.label}
        </button>
      ))}
      {first.truncatedCells?.length ? <span className="muted">Values cut for display are exported as shown.</span> : null}
      {problem ? (
        <span className="result-error" role="alert">
          {problem}
        </span>
      ) : null}
    </span>
  );
}
