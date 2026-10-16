import { useVirtualizer } from '@tanstack/react-virtual';
import { useMemo, useRef, useState } from 'react';

const PAGE_SIZE = 200;
const ROW_HEIGHT = 26;
const NUMERIC = new Set(['int2', 'int4', 'int8', 'oid', 'float4', 'float8', 'numeric', 'money']);

/** Compares two cell values for a local sort: NULLs last, numbers as numbers, everything else as text. */
export function compareCells(a, b, type) {
  if (a === b) return 0;
  if (a === null) return 1;
  if (b === null) return -1;
  if (NUMERIC.has(type)) {
    const x = Number(a);
    const y = Number(b);
    if (!Number.isNaN(x) && !Number.isNaN(y) && x !== y) return x < y ? -1 : 1;
  }
  return a < b ? -1 : 1;
}

function rowsLabel(n) {
  return `${n.toLocaleString('en-GB')} ${n === 1 ? 'row' : 'rows'}`;
}

/**
 * One row-returning result (plan Phase 8): virtualised rows, headers with PostgreSQL type names, SQL NULL shown
 * distinctly from text, truncated cells and results marked, a local sort that says so, and pages of 200 rows.
 * Values are always rendered as text (React escapes them), never as HTML.
 */
export default function ResultGrid({ result }) {
  const { columns, rows } = result;
  const cut = useMemo(() => new Set((result.truncatedCells || []).map(([r, c]) => `${r}:${c}`)), [result.truncatedCells]);
  // Columns have no identity besides position and name (names can repeat: SELECT 1 AS a, 2 AS a).
  const colKeys = useMemo(() => columns.map((col, i) => `${i}:${col.name}`), [columns]);
  const [sort, setSort] = useState(null); // {column, dir}
  const [page, setPage] = useState(0);
  const order = useMemo(() => {
    const idx = rows.map((_, i) => i);
    if (!sort) return idx;
    const type = columns[sort.column].type;
    idx.sort((i, j) => compareCells(rows[i][sort.column], rows[j][sort.column], type) * sort.dir || i - j);
    return idx;
  }, [rows, columns, sort]);
  const pages = Math.max(1, Math.ceil(order.length / PAGE_SIZE));
  const current = Math.min(page, pages - 1);
  const pageRows = order.slice(current * PAGE_SIZE, (current + 1) * PAGE_SIZE);

  const scroller = useRef(null);
  // TanStack Virtual returns non-memoisable functions, so the React Compiler skips this component; that is fine
  // (it re-renders on scroll by design).
  // eslint-disable-next-line react-hooks/incompatible-library
  const virtual = useVirtualizer({
    count: pageRows.length,
    getScrollElement: () => scroller.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 12,
    initialRect: { width: 1024, height: 600 },
  });
  const items = virtual.getVirtualItems();
  const top = items.length ? items[0].start : 0;
  const bottom = items.length ? virtual.getTotalSize() - items.at(-1).end : 0;

  const toggleSort = (column) => {
    setPage(0);
    setSort((s) => {
      if (!s || s.column !== column) return { column, dir: 1 };
      if (s.dir === 1) return { column, dir: -1 };
      return null;
    });
  };

  return (
    <div className="result-grid">
      <div className="result-meta">
        <span>{rowsLabel(rows.length)}</span>
        {result.truncated ? (
          <span className="result-truncated" role="note">
            {result.truncatedBy === 'bytes'
              ? 'Result cut short: the size limit was reached. Refine the query to see the rest.'
              : `Only the first ${rowsLabel(rows.length)} are shown (limit reached).`}
          </span>
        ) : null}
        {sort ? <span className="result-sorted">Sorted locally (only the rows shown here; not ORDER BY)</span> : null}
      </div>
      <div className="result-scroll" ref={scroller}>
        <table className="grid" aria-rowcount={pageRows.length + 1} aria-colcount={columns.length}>
          <thead>
            <tr>
              <th scope="col" className="grid-rownum">
                #
              </th>
              {columns.map((c, i) => {
                let ariaSort = 'none';
                if (sort?.column === i) ariaSort = sort.dir === 1 ? 'ascending' : 'descending';
                return (
                  <th key={colKeys[i]} scope="col" aria-sort={ariaSort}>
                    <button type="button" className="grid-sort" onClick={() => toggleSort(i)} title="Sort these rows locally">
                      <span className="grid-col-name">{c.name}</span>{' '}
                      <span className="grid-col-type">{c.type}</span>
                    </button>
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {top > 0 ? (
              <tr className="grid-spacer" role="presentation" style={{ height: top }}>
                <td colSpan={columns.length + 1} />
              </tr>
            ) : null}
            {items.map((item) => {
              const r = pageRows[item.index];
              return (
                <tr key={r} style={{ height: ROW_HEIGHT }}>
                  <td className="grid-rownum">{r + 1}</td>
                  {rows[r].map((v, c) => (
                    <td key={colKeys[c]} className={v === null ? 'cell-null' : undefined}>
                      {v === null ? <span className="null-marker">NULL</span> : v}
                      {cut.has(`${r}:${c}`) ? <span className="cell-cut" title="This value was cut at the cell size limit"> …(cut)</span> : null}
                    </td>
                  ))}
                </tr>
              );
            })}
            {bottom > 0 ? (
              <tr className="grid-spacer" role="presentation" style={{ height: bottom }}>
                <td colSpan={columns.length + 1} />
              </tr>
            ) : null}
          </tbody>
        </table>
      </div>
      {pages > 1 ? (
        <nav className="pager" aria-label="Result pages">
          <button type="button" className="btn" disabled={current === 0} onClick={() => setPage(current - 1)}>
            Previous
          </button>
          <span>
            Rows {(current * PAGE_SIZE + 1).toLocaleString('en-GB')}–{Math.min((current + 1) * PAGE_SIZE, order.length).toLocaleString('en-GB')} of{' '}
            {order.length.toLocaleString('en-GB')}
          </span>
          <button type="button" className="btn" disabled={current >= pages - 1} onClick={() => setPage(current + 1)}>
            Next
          </button>
        </nav>
      ) : null}
    </div>
  );
}
