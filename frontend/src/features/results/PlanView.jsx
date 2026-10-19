import { useState } from 'react';
import { StatusBadge } from '../../components/common/index.jsx';

/**
 * The plan in an EXPLAIN (FORMAT JSON) result, or null when the result is anything else (another format, a truncated
 * cell, text that isn't a plan). Only ever parsed as JSON data; nothing in it is rendered as HTML.
 */
export function parsePlan(result) {
  if (!result || result.columns?.length !== 1 || result.rows?.length !== 1) return null;
  if ((result.truncatedCells || []).length) return null;
  const cell = result.rows[0][0];
  if (typeof cell !== 'string') return null;
  try {
    const doc = JSON.parse(cell);
    const top = Array.isArray(doc) ? doc[0] : null;
    return top && typeof top === 'object' && top.Plan ? top : null;
  } catch {
    return null;
  }
}

const DETAIL_KEYS = ['Index Cond', 'Recheck Cond', 'Hash Cond', 'Merge Cond', 'Join Filter', 'Filter', 'Rows Removed by Filter', 'Sort Key', 'Group Key', 'Sort Method'];

function ms(n) {
  return `${Number(n).toFixed(3)} ms`;
}

/** How far the planner's row estimate was from the actual rows (×10 or more is worth a look). */
export function misestimate(node) {
  if (typeof node['Actual Rows'] !== 'number' || typeof node['Plan Rows'] !== 'number') return null;
  const actual = Math.max(1, node['Actual Rows']);
  const planned = Math.max(1, node['Plan Rows']);
  const factor = Math.max(actual / planned, planned / actual);
  return factor >= 10 ? Math.round(factor) : null;
}

function title(node) {
  let t = node['Node Type'];
  if (node['Join Type'] && /Join|Nested Loop/.test(t)) t = `${t} (${node['Join Type']})`;
  if (node['Relation Name']) {
    const rel = node.Schema ? `${node.Schema}.${node['Relation Name']}` : node['Relation Name'];
    t += ` on ${rel}${node.Alias && node.Alias !== node['Relation Name'] ? ` ${node.Alias}` : ''}`;
  }
  if (node['Index Name']) t += ` using ${node['Index Name']}`;
  if (node['CTE Name']) t += ` (CTE ${node['CTE Name']})`;
  return t;
}

function PlanNode({ node }) {
  const off = misestimate(node);
  const analyzed = typeof node['Actual Total Time'] === 'number';
  const details = DETAIL_KEYS.filter((k) => node[k] !== undefined).map((k) => [k, Array.isArray(node[k]) ? node[k].join(', ') : String(node[k])]);
  const buffers = ['Shared Hit Blocks', 'Shared Read Blocks'].every((k) => typeof node[k] === 'number')
    ? `buffers: ${node['Shared Hit Blocks']} hit, ${node['Shared Read Blocks']} read`
    : null;
  return (
    <li className="plan-node">
      <div className="plan-node-head">
        <strong>{title(node)}</strong>
        <span className="muted">
          cost {node['Startup Cost']}..{node['Total Cost']} · {node['Plan Rows']} rows estimated
        </span>
        {analyzed ? (
          <span>
            actual {ms(node['Actual Startup Time'])}..{ms(node['Actual Total Time'])} · {node['Actual Rows']} rows × {node['Actual Loops']}{' '}
            {node['Actual Loops'] === 1 ? 'loop' : 'loops'}
          </span>
        ) : null}
        {off ? <StatusBadge tone="warn" title="The planner's row estimate differs from the actual rows by this factor">estimate off ×{off}</StatusBadge> : null}
      </div>
      {details.length || buffers ? (
        <ul className="plan-details">
          {details.map(([k, v]) => (
            <li key={k}>
              <span className="muted">{k}:</span> {v}
            </li>
          ))}
          {buffers ? <li className="muted">{buffers}</li> : null}
        </ul>
      ) : null}
      {node.Plans?.length ? (
        <ul className="plan-children">
          {node.Plans.map((child, i) => (
            <PlanNode key={i} node={child} />
          ))}
        </ul>
      ) : null}
    </li>
  );
}

/**
 * EXPLAIN viewer (admin Phase 3): the plan as a tree with costs, estimated and (with ANALYZE) actual rows and times,
 * conditions, buffers, and nodes whose estimate was off by ten times or more. "Show as table" shows the raw result.
 */
export default function PlanView({ plan, raw }) {
  const [asTable, setAsTable] = useState(false);
  const analyzed = typeof plan['Execution Time'] === 'number';
  return (
    <div className="plan-view">
      <div className="result-toolbar">
        <span>
          {analyzed ? 'Plan with actual times (EXPLAIN ANALYZE)' : 'Estimated plan (EXPLAIN): the statement was not run'}
          {typeof plan['Planning Time'] === 'number' ? ` · planning ${ms(plan['Planning Time'])}` : ''}
          {analyzed ? ` · execution ${ms(plan['Execution Time'])}` : ''}
        </span>
        <button type="button" className="btn" onClick={() => setAsTable((t) => !t)} aria-pressed={asTable}>
          {asTable ? 'Show as plan' : 'Show as table'}
        </button>
      </div>
      {asTable ? (
        raw
      ) : (
        <ul className="plan-tree" aria-label="Query plan">
          <PlanNode node={plan.Plan} />
        </ul>
      )}
    </div>
  );
}
