import React from 'react';
import { Link, useParams } from 'react-router-dom';
import { RESPONSE_OUTCOMES, privacyRequestApi } from '../api/privacyRequestApi';
import type { DispatchView, PrivacyRequestSummary, ServiceResponse } from '../types/api';
import { ErrorState, Loading, Modal, StatusBadge } from '../components/ui';
import { formatInstant } from '../utils/format';
import { friendlyErrorMessage } from '../utils/errors';

const LIFECYCLE: Array<{ stage: string; meaning: string }> = [
  { stage: 'DRAFT', meaning: 'Stored locally. Nothing sent; submit for review to proceed.' },
  { stage: 'READY_FOR_REVIEW', meaning: 'Reviewed content, exact version pinned. Approval decides nothing is sent yet.' },
  { stage: 'APPROVED', meaning: 'You approved this exact version. Still nothing sent — dispatch is separate.' },
  { stage: 'DISPATCHED', meaning: 'Explicitly confirmed dispatch; the backend hands the snapshot to the provider.' },
  { stage: 'ACCEPTED / FAILED', meaning: 'Provider outcome recorded. Accepted delivery cannot be unsent.' },
];

/**
 * Request lifecycle page. Approval and dispatch are two strictly separate
 * user actions: approving never calls dispatch, and dispatch is only ever
 * invoked from the confirmation modal after the understanding checkbox.
 */
export function PrivacyRequestDetailPage(): React.JSX.Element {
  const { id } = useParams<{ id: string }>();
  const [request, setRequest] = React.useState<PrivacyRequestSummary | null>(null);
  const [dispatch, setDispatch] = React.useState<DispatchView | null>(null);
  const [dispatchMissing, setDispatchMissing] = React.useState(false);
  const [responses, setResponses] = React.useState<ServiceResponse[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<unknown>(null);
  const [actionError, setActionError] = React.useState<unknown>(null);
  const [busy, setBusy] = React.useState(false);

  const [editing, setEditing] = React.useState(false);
  const [subject, setSubject] = React.useState('');
  const [body, setBody] = React.useState('');
  const [approving, setApproving] = React.useState(false);
  const [dispatchOpen, setDispatchOpen] = React.useState(false);
  const [understood, setUnderstood] = React.useState(false);
  const [sending, setSending] = React.useState(false);

  const [outcome, setOutcome] = React.useState('completed');
  const [summary, setSummary] = React.useState('');

  const load = React.useCallback(async () => {
    if (!id) return;
    setLoading(true);
    setError(null);
    try {
      const row = await privacyRequestApi.get(id);
      setRequest(row);
      setSubject(row.subject);
      setBody(row.body);
      try {
        setDispatch(await privacyRequestApi.dispatchStatus(id));
        setDispatchMissing(false);
      } catch {
        setDispatch(null);
        setDispatchMissing(true);
      }
      try {
        setResponses(await privacyRequestApi.responses(id));
      } catch {
        setResponses([]);
      }
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  }, [id]);

  React.useEffect(() => {
    void load();
  }, [load]);

  if (!id) {
    return (
      <div className="alert alert-danger" role="alert">
        <p>Missing request id.</p>
      </div>
    );
  }

  const run = async (fn: () => Promise<void>): Promise<void> => {
    setBusy(true);
    setActionError(null);
    try {
      await fn();
      await load();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  const saveEdit = (): Promise<void> =>
    run(async () => {
      await privacyRequestApi.patch(id, subject, body);
      setEditing(false);
    });

  /** DRAFT → READY_FOR_REVIEW without changing content (backend PATCH with {}). */
  const submitForReview = (): Promise<void> =>
    run(async () => {
      await privacyRequestApi.patch(id);
    });

  const approve = (): Promise<void> =>
    run(async () => {
      if (!request) return;
      // Approve the exact version currently shown; stale views are rejected.
      await privacyRequestApi.approve(id, request.version);
      setApproving(false);
    });

  /** The ONLY call site for dispatch in the entire frontend. */
  const confirmDispatch = async (): Promise<void> => {
    if (!understood || !request) return;
    setSending(true);
    setActionError(null);
    try {
      const result = await privacyRequestApi.dispatch(id, request.version, crypto.randomUUID());
      setDispatch(result);
      setDispatchMissing(false);
      setDispatchOpen(false);
      setUnderstood(false);
      await load();
    } catch (err) {
      setActionError(err);
    } finally {
      setSending(false);
    }
  };

  const recordResponse = (): Promise<void> =>
    run(async () => {
      await privacyRequestApi.recordResponse(id, outcome, summary || undefined);
      setSummary('');
    });

  const stageIndex = (status: string): number => {
    if (status === 'DRAFT') return 0;
    if (status === 'READY_FOR_REVIEW') return 1;
    if (status === 'APPROVED') return 2;
    return 2;
  };
  return (
    <div>
      <p>
        <Link to="/privacy-requests">← Back to requests</Link>
      </p>
      {loading && <Loading label="Loading request" />}
      {Boolean(error) && <ErrorState error={error} onRetry={() => load()} />}
      {!loading && !error && request && (
        <>
          <h1>
            {request.service || request.domain} <StatusBadge value={request.status} />
          </h1>

          <div className="card" aria-label="Lifecycle">
            <h2>Lifecycle</h2>
            <ol className="timeline">
              {LIFECYCLE.map(({ stage, meaning }, i) => (
                <li key={stage} className={i < stageIndex(request.status) ? 'done' : i === stageIndex(request.status) ? 'current' : ''}>
                  <strong>{stage}</strong>
                  <div className="small muted">{meaning}</div>
                </li>
              ))}
            </ol>
            <p className="small muted">
              Version v{request.version}
              {request.approvedVersion != null && ` · approved v${request.approvedVersion}`}
              {' · '}expires {formatInstant(request.expiresAt)}
            </p>
            <p className="small">
              <strong>Approval is not dispatch.</strong> Even an approved request sends
              nothing until you confirm dispatch separately below.
            </p>
          </div>

          <div className="card">
            <h2>Request content</h2>
            {!editing ? (
              <>
                <p>
                  <strong>{request.subject}</strong>
                </p>
                <pre className="content">{request.body}</pre>
                <div className="btn-row">
                  {['DRAFT', 'READY_FOR_REVIEW', 'APPROVED'].includes(request.status) && (
                    <button type="button" className="btn" disabled={busy} onClick={() => setEditing(true)}>
                      Edit request
                    </button>
                  )}
                </div>
              </>
            ) : (
              <>
                <div className="field">
                  <label htmlFor="edit-subject">Subject</label>
                  <input
                    id="edit-subject"
                    type="text"
                    value={subject}
                    onChange={(e) => setSubject(e.target.value)}
                    maxLength={200}
                  />
                </div>
                <div className="field">
                  <label htmlFor="edit-body">Body</label>
                  <textarea
                    id="edit-body"
                    value={body}
                    onChange={(e) => setBody(e.target.value)}
                    maxLength={8000}
                  />
                </div>
                <div className="alert alert-warning" role="note">
                  <p className="small" style={{ margin: 0 }}>
                    Editing returns the request to review and may invalidate a previous
                    approval — re-approval of the new version is required before dispatch.
                  </p>
                </div>
                <div className="btn-row">
                  <button type="button" className="btn btn-primary" disabled={busy} onClick={saveEdit}>
                    Save changes
                  </button>
                  <button type="button" className="btn" disabled={busy} onClick={() => setEditing(false)}>
                    Discard
                  </button>
                </div>
              </>
            )}
          </div>

          {Boolean(actionError) && (
            <div className="alert alert-danger" role="alert">
              <p>{friendlyErrorMessage(actionError)}</p>
            </div>
          )}

          <div className="card">
            <h2>Review &amp; approval</h2>
            {request.status === 'APPROVED' ? (
              <p>
                <StatusBadge value="APPROVED" /> v{request.approvedVersion} — nothing has been
                sent. Dispatch requires a separate confirmation below.
              </p>
            ) : request.status === 'DRAFT' ? (
              <>
                <p className="small muted">
                  This request is a draft. Submit it for review first — approval is only
                  possible once it is ready for review.
                </p>
                <div className="btn-row">
                  <button type="button" className="btn btn-primary" disabled={busy} onClick={submitForReview}>
                    Submit for review
                  </button>
                </div>
              </>
            ) : request.status === 'READY_FOR_REVIEW' ? (
              <>
                {!approving ? (
                  <div className="btn-row">
                    <button type="button" className="btn btn-primary" disabled={busy} onClick={() => setApproving(true)}>
                      Approve Request
                    </button>
                  </div>
                ) : (
                  <>
                    <p>
                      You are approving <strong>this exact version (v{request.version})</strong>{' '}
                      of the request. Approval does not send anything.
                    </p>
                    <div className="btn-row">
                      <button type="button" className="btn btn-primary" disabled={busy} onClick={approve}>
                        Confirm approval of v{request.version}
                      </button>
                      <button type="button" className="btn" disabled={busy} onClick={() => setApproving(false)}>
                        Back
                      </button>
                    </div>
                  </>
                )}
              </>
            ) : (
              <p className="small muted">
                This request is {request.status}; no further review actions are available.
              </p>
            )}
          </div>

          <div className="card">
            <h2>Dispatch</h2>
            {request.status !== 'APPROVED' && (
              <p className="small muted">
                Dispatch unlocks after approval — and even then it requires a separate
                explicit confirmation. Nothing here sends automatically.
              </p>
            )}
            {dispatchMissing && !dispatch && (
              <p className="muted small">No dispatch recorded for this request.</p>
            )}
            {dispatch && (
              <dl className="kv">
                <dt>Status</dt>
                <dd>
                  <StatusBadge value={dispatch.status} />
                </dd>
                <dt>Destination</dt>
                <dd>{dispatch.destination}</dd>
                <dt>Request version</dt>
                <dd>v{dispatch.requestVersion}</dd>
                <dt>Attempts</dt>
                <dd>{dispatch.attempts}</dd>
                {dispatch.failureCategory && (
                  <>
                    <dt>Failure</dt>
                    <dd>{dispatch.failureCategory}</dd>
                  </>
                )}
              </dl>
            )}
            <div className="btn-row">
              <button
                type="button"
                className="btn btn-danger"
                disabled={busy || request.status !== 'APPROVED'}
                onClick={() => {
                  setUnderstood(false);
                  setDispatchOpen(true);
                }}
                title={
                  request.status !== 'APPROVED'
                    ? 'Only approved requests can be dispatched'
                    : 'Open final dispatch confirmation'
                }
              >
                Dispatch…
              </button>
              {dispatch && !['ACCEPTED', 'CANCELLED'].includes(dispatch.status) && (
                <button
                  type="button"
                  className="btn"
                  disabled={busy}
                  onClick={() =>
                    run(async () => {
                      await privacyRequestApi.cancelDispatch(id);
                    })
                  }
                >
                  Cancel dispatch
                </button>
              )}
              {request.status !== 'CANCELLED' && request.status !== 'EXPIRED' && (
                <button
                  type="button"
                  className="btn"
                  disabled={busy}
                  onClick={() =>
                    run(async () => {
                      await privacyRequestApi.cancel(id);
                    })
                  }
                >
                  Cancel request
                </button>
              )}
              <Link className="btn" to={`/privacy-requests/${id}/tracking`}>
                Tracking
              </Link>
            </div>
          </div>

          <div className="card">
            <h2>Service responses</h2>
            <p className="small muted">
              Record what the service did — manual intake only, never verified evidence.
            </p>
            {request.status !== 'APPROVED' ? (
              <p className="small muted">
                Responses can only be recorded for approved requests.
              </p>
            ) : (
              <>
            <div className="field">
              <label htmlFor="resp-outcome">Outcome</label>
              <select id="resp-outcome" value={outcome} onChange={(e) => setOutcome(e.target.value)}>
                {RESPONSE_OUTCOMES.map((o) => (
                  <option key={o} value={o}>
                    {o}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor="resp-summary">Summary (optional)</label>
              <input
                id="resp-summary"
                type="text"
                maxLength={2000}
                value={summary}
                onChange={(e) => setSummary(e.target.value)}
              />
            </div>
            <div className="btn-row">
              <button type="button" className="btn" disabled={busy} onClick={recordResponse}>
                Record response
              </button>
            </div>
              </>
            )}
            {responses.length > 0 && (
              <ul className="small">
                {responses.map((r) => (
                  <li key={r.id}>
                    <StatusBadge value={r.outcome} /> {r.summary} · {formatInstant(r.recordedAt)}
                  </li>
                ))}
              </ul>
            )}
          </div>

          {dispatchOpen && (
            <Modal title="Final confirmation required" onClose={() => setDispatchOpen(false)}>
              <p>
                <strong>GhostClean is about to submit this privacy/deletion request to the
                verified destination.</strong>
              </p>
              <dl className="kv">
                <dt>Service</dt>
                <dd>{request.service || request.domain}</dd>
                <dt>Type</dt>
                <dd>{request.requestType}</dd>
                <dt>Approved version</dt>
                <dd>v{request.approvedVersion ?? request.version}</dd>
                <dt>Destination</dt>
                <dd>Resolved server-side by the backend registry (shown after sending).</dd>
              </dl>
              <div className="alert alert-warning" role="note">
                <p className="small" style={{ margin: 0 }}>
                  GhostClean does not guarantee deletion. The service decides how to process
                  the request, and accepted delivery cannot be unsent.
                </p>
              </div>
              <label className="checkbox-row" htmlFor="dispatch-understand">
                <input
                  id="dispatch-understand"
                  type="checkbox"
                  checked={understood}
                  onChange={(e) => setUnderstood(e.target.checked)}
                />
                <span>I understand that this will send the approved request to the destination.</span>
              </label>
              <div className="btn-row">
                <button
                  type="button"
                  className="btn btn-danger"
                  disabled={!understood || sending}
                  onClick={confirmDispatch}
                >
                  {sending ? 'Sending…' : 'Send Deletion Request'}
                </button>
                <button
                  type="button"
                  className="btn"
                  disabled={sending}
                  onClick={() => setDispatchOpen(false)}
                >
                  Cancel
                </button>
              </div>
            </Modal>
          )}
        </>
      )}
    </div>
  );
}
