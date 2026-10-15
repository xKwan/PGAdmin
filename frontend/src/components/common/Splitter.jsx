import { useCallback, useRef, useState } from 'react';

const clamp = (v, min, max) => Math.min(max, Math.max(min, v));

/**
 * Two resizable panes (the DBeaver-style layout), horizontal or vertical. The divider works with the mouse and the
 * keyboard (arrow keys, Home, End) and exposes its position as an ARIA separator.
 * @param {{direction?: 'horizontal'|'vertical', initial?: number, min?: number, max?: number, label: string,
 *          first: React.ReactNode, second: React.ReactNode}} props  sizes are percentages of the first pane
 */
export function Splitter({ direction = 'horizontal', initial = 30, min = 10, max = 90, label, first, second }) {
  const [size, setSize] = useState(initial);
  const box = useRef(null);
  const horizontal = direction === 'horizontal';

  const onPointerDown = useCallback(
    (e) => {
      e.preventDefault();
      const rect = box.current.getBoundingClientRect();
      const move = (ev) => {
        const pos = horizontal ? (ev.clientX - rect.left) / rect.width : (ev.clientY - rect.top) / rect.height;
        if (Number.isFinite(pos)) setSize(clamp(Math.round(pos * 100), min, max));
      };
      const up = () => {
        window.removeEventListener('pointermove', move);
        window.removeEventListener('pointerup', up);
      };
      window.addEventListener('pointermove', move);
      window.addEventListener('pointerup', up);
    },
    [horizontal, min, max],
  );

  const onKeyDown = (e) => {
    const step = e.shiftKey ? 10 : 2;
    const back = horizontal ? 'ArrowLeft' : 'ArrowUp';
    const fwd = horizontal ? 'ArrowRight' : 'ArrowDown';
    if (e.key === back) setSize((s) => clamp(s - step, min, max));
    else if (e.key === fwd) setSize((s) => clamp(s + step, min, max));
    else if (e.key === 'Home') setSize(min);
    else if (e.key === 'End') setSize(max);
    else return;
    e.preventDefault();
  };

  return (
    <div ref={box} className={`split split-${direction}`}>
      <div className="split-pane" style={{ flexBasis: `${size}%` }}>
        {first}
      </div>
      {/* WAI-ARIA window splitter: a focusable separator is an interactive widget (ARIA 1.2), so Sonar's
          non-interactive-element rules (S6845, S6847) don't apply; <hr> can't take focus or keys. */}
      <div // NOSONAR
        className="split-divider"
        role="separator"
        tabIndex={0} // NOSONAR
        aria-label={label}
        aria-orientation={horizontal ? 'vertical' : 'horizontal'}
        aria-valuenow={size}
        aria-valuemin={min}
        aria-valuemax={max}
        onPointerDown={onPointerDown}
        onKeyDown={onKeyDown}
      />
      <div className="split-pane split-pane-rest">{second}</div>
    </div>
  );
}
