// Common UI components (plan Phase 5). Every value is rendered as text: no HTML injection anywhere (T13).
import { useEffect, useId, useRef } from 'react';

/** A small coloured status label. `tone`: neutral | ok | warn | danger | info. */
export function StatusBadge({ tone = 'neutral', children, title }) {
  return (
    <span className={`badge badge-${tone}`} title={title}>
      {children}
    </span>
  );
}

/** Busy indicator with an accessible label. */
export function Spinner({ label = 'Loading' }) {
  return (
    <span className="spinner" role="status" aria-live="polite">
      <span className="spinner-dot" aria-hidden="true" />
      <span className="visually-hidden">{label}</span>
    </span>
  );
}

/** Shown when there is nothing to show yet. */
export function EmptyState({ title, children, action }) {
  return (
    <div className="empty-state">
      <p className="empty-title">{title}</p>
      {children ? <div className="empty-body">{children}</div> : null}
      {action ? <div className="empty-action">{action}</div> : null}
    </div>
  );
}

/**
 * An error from the API or the app. Shows the message, the server's code and request ID (for support),
 * and an optional retry button. Never shows stack traces.
 */
export function ErrorPanel({ title = 'Something went wrong', error, onRetry }) {
  const message = error?.message || String(error ?? 'Unknown error');
  return (
    <div className="error-panel" role="alert">
      <p className="error-title">{title}</p>
      <p className="error-message">{message}</p>
      {error?.code || error?.requestId ? (
        <p className="error-meta">
          {error?.code ? <span>Code: {error.code}</span> : null}
          {error?.requestId ? <span>Request ID: {error.requestId}</span> : null}
        </p>
      ) : null}
      {onRetry ? (
        <button type="button" className="btn" onClick={onRetry}>
          Try again
        </button>
      ) : null}
    </div>
  );
}

/**
 * A modal confirmation. Focus moves to the dialog; Escape cancels. Confirmations are a convenience, never a
 * security control (plan Phase 9).
 */
export function ConfirmDialog({ open, title, children, confirmLabel = 'Confirm', danger = false, onConfirm, onCancel }) {
  const titleId = useId();
  const cancelRef = useRef(null);
  useEffect(() => {
    if (!open) return undefined;
    cancelRef.current?.focus();
    const onKey = (e) => {
      if (e.key === 'Escape') onCancel?.();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onCancel]);
  if (!open) return null;
  return (
    <div className="dialog-backdrop">
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <h2 id={titleId} className="dialog-title">
          {title}
        </h2>
        <div className="dialog-body">{children}</div>
        <div className="dialog-actions">
          <button type="button" className="btn" ref={cancelRef} onClick={onCancel}>
            Cancel
          </button>
          <button type="button" className={danger ? 'btn btn-danger' : 'btn btn-primary'} onClick={onConfirm}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

/** Transient notifications. `toasts`: [{id, tone, text}]. */
export function ToastList({ toasts, onDismiss }) {
  return (
    <div className="toasts" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className={`toast toast-${t.tone || 'info'}`} role="status">
          <span>{t.text}</span>
          <button type="button" className="toast-close" aria-label="Dismiss notification" onClick={() => onDismiss(t.id)}>
            ×
          </button>
        </div>
      ))}
    </div>
  );
}
