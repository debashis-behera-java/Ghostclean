import React from 'react';
import { friendlyErrorMessage } from '../utils/errors';

/** Generic loading indicator. */
export function Loading({ label }: { label: string }): React.JSX.Element {
  return (
    <div className="card" role="status" aria-live="polite" aria-label={label}>
      <p className="muted" style={{ margin: 0 }}>
        {label}…
      </p>
    </div>
  );
}

/** Error panel with retry. Never renders raw payloads. */
export function ErrorState({
  error,
  onRetry,
  retryLabel = 'Try again',
}: {
  error: unknown;
  onRetry?: () => void;
  retryLabel?: string;
}): React.JSX.Element {
  return (
    <div className="alert alert-danger" role="alert">
      <p>
        <strong>Something went wrong.</strong>
      </p>
      <p className="muted">{friendlyErrorMessage(error)}</p>
      {onRetry && (
        <div className="btn-row">
          <button type="button" className="btn" onClick={onRetry}>
            {retryLabel}
          </button>
        </div>
      )}
    </div>
  );
}

/** Empty collection panel. */
export function EmptyState({ title, body }: { title: string; body?: string }): React.JSX.Element {
  return (
    <div className="card" role="status">
      <h3>{title}</h3>
      {body && <p className="muted">{body}</p>}
    </div>
  );
}

/** Status pill with conservative coloring; unknown values render neutral. */
export function StatusBadge({ value }: { value: string | null | undefined }): React.JSX.Element {
  const normalized = (value ?? 'UNKNOWN').toUpperCase();
  const cls = (() => {
    if (['HIGH', 'VERIFIED', 'KNOWN', 'ACCEPTED', 'APPROVED', 'CONNECTED', 'ACTIVE', 'COMPLETED'].includes(normalized))
      return 'badge-verified';
    if (['MEDIUM', 'READY_FOR_REVIEW', 'PENDING', 'WARNING', 'PARTIAL'].includes(normalized)) return 'badge-warning';
    if (['FAILED', 'EXPIRED', 'REJECTED', 'CANCELLED', 'DENIED', 'ERROR'].includes(normalized)) return 'badge-danger';
    if (['LOW', 'INFO', 'QUEUED', 'SENDING', 'DRAFT', 'DETECTED', 'REVIEWED'].includes(normalized)) return 'badge-info';
    return '';
  })();
  return <span className={`badge ${cls}`.trim()}>{normalized}</span>;
}

/** Accessible modal dialog (Escape closes, backdrop click closes). */
export function Modal({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: React.ReactNode;
}): React.JSX.Element {
  const panelRef = React.useRef<HTMLDivElement>(null);

  React.useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    panelRef.current?.querySelector<HTMLElement>('button, input, [tabindex]')?.focus();
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div
      className="modal-backdrop"
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        ref={panelRef}
      >
        <h2>{title}</h2>
        {children}
      </div>
    </div>
  );
}

/** Dry-run banner used on every preview surface. */
export function DryRunBanner(): React.JSX.Element {
  return (
    <div className="safety-banner" role="note" aria-label="Dry run notice">
      <strong>DRY RUN — nothing has been sent.</strong>
      <span className="muted">
        This preview does not contact the service. No deletion has occurred. Creating a
        request only stores a draft; approval and dispatch are separate, explicit actions.
      </span>
    </div>
  );
}
