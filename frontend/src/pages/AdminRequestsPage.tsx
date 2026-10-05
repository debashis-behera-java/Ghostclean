import React from 'react';
import { oauthApi } from '../api/oauthApi';
import type { GoogleApprovalQueueItem } from '../types/api';
import { useApi } from '../hooks/useApi';
import { EmptyState, ErrorState, Loading, Modal, StatusBadge } from '../components/ui';
import { formatInstant } from '../utils/format';
import { friendlyErrorMessage } from '../utils/errors';

/**
 * Admin review queue for Google connection approval requests. Approve and
 * reject record the business decision only — Google OAuth still applies in
 * full afterward. Rejection reasons are capped by the backend; no tokens,
 * codes, or mailbox content ever appear here.
 */
export function AdminRequestsPage(): React.JSX.Element {
  const { data, loading, error, reload } = useApi(() => oauthApi.adminGoogleRequests());
  const [actionError, setActionError] = React.useState<unknown>(null);
  const [busyId, setBusyId] = React.useState<string | null>(null);
  const [rejectTarget, setRejectTarget] = React.useState<GoogleApprovalQueueItem | null>(null);
  const [reason, setReason] = React.useState('');

  const approve = async (item: GoogleApprovalQueueItem): Promise<void> => {
    if (!window.confirm(`Approve the Google connection request from ${item.userEmail}?`)) return;
    setBusyId(item.requestId);
    setActionError(null);
    try {
      await oauthApi.adminApproveGoogleRequest(item.requestId);
      reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusyId(null);
    }
  };

  const reject = async (): Promise<void> => {
    if (!rejectTarget) return;
    setBusyId(rejectTarget.requestId);
    setActionError(null);
    try {
      await oauthApi.adminRejectGoogleRequest(rejectTarget.requestId, reason || undefined);
      setRejectTarget(null);
      setReason('');
      reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div>
      <h1>Google Connection Requests</h1>
      <p className="muted">
        Approve or reject GhostClean-level connection requests. Approval lets the user
        start Google OAuth — Google's own consent screen still applies afterward.
      </p>
      <div className="alert" role="note">
        <p className="small" style={{ margin: 0 }}>
          Google-side setup is manual: after approving, the user's Google account must
          be listed under Audience → Test users in the Google Auth Platform console
          (<span className="mono">console.cloud.google.com/auth/audience</span>),
          otherwise Google answers “access denied”. GhostClean cannot automate this —
          Google offers no supported Test User provisioning API.
        </p>
      </div>
      {Boolean(actionError) && (
        <div className="alert alert-danger" role="alert">
          <p>{friendlyErrorMessage(actionError)}</p>
        </div>
      )}
      {loading && <Loading label="Loading approval requests" />}
      {Boolean(error) && <ErrorState error={error} onRetry={reload} />}
      {!loading && !error && (!data || data.length === 0) && (
        <EmptyState title="No connection requests." body="New user requests will appear here." />
      )}
      {!loading && !error && data && data.length > 0 && (
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th>User</th>
                <th>Requested</th>
                <th>Status</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {data.map((item) => (
                <tr key={item.requestId}>
                  <td data-label="User">{item.userEmail}</td>
                  <td data-label="Requested">{formatInstant(item.requestedAt)}</td>
                  <td data-label="Status">
                    <StatusBadge value={item.status} />
                    {item.status === 'REJECTED' && item.rejectionReason && (
                      <div className="small muted">{item.rejectionReason}</div>
                    )}
                  </td>
                  <td data-label="Action">
                    {item.status === 'PENDING' ? (
                      <div className="btn-row" style={{ marginTop: 0 }}>
                        <button
                          type="button"
                          className="btn btn-primary"
                          disabled={busyId === item.requestId}
                          onClick={() => approve(item)}
                        >
                          Approve
                        </button>
                        <button
                          type="button"
                          className="btn"
                          disabled={busyId === item.requestId}
                          onClick={() => {
                            setRejectTarget(item);
                            setReason('');
                          }}
                        >
                          Reject
                        </button>
                      </div>
                    ) : (
                      <span className="small muted">{item.status === 'APPROVED' ? 'Approved' : item.status === 'REJECTED' ? 'Rejected' : item.status}</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {rejectTarget && (
        <Modal title="Reject connection request" onClose={() => setRejectTarget(null)}>
          <p>
            Reject the request from <strong>{rejectTarget.userEmail}</strong>? The user may
            request again after the review cooldown.
          </p>
          <div className="field">
            <label htmlFor="reject-reason">Reason (optional, shown to the user)</label>
            <input
              id="reject-reason"
              type="text"
              maxLength={300}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="e.g. Please retry with your primary account"
            />
          </div>
          <div className="btn-row">
            <button type="button" className="btn btn-danger" disabled={busyId !== null} onClick={reject}>
              Confirm rejection
            </button>
            <button type="button" className="btn" disabled={busyId !== null} onClick={() => setRejectTarget(null)}>
              Cancel
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
