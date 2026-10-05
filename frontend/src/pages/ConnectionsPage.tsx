import React from 'react';
import { oauthApi } from '../api/oauthApi';
import type { GoogleApprovalStatus } from '../types/api';
import { useApi } from '../hooks/useApi';
import { EmptyState, ErrorState, Loading, StatusBadge } from '../components/ui';
import { formatInstant } from '../utils/format';
import { friendlyErrorMessage } from '../utils/errors';

/**
 * Mailbox connections. Displays provider, masked email, status, scopes, and
 * dates — never tokens, codes, or encrypted material (the backend never
 * returns them; the UI has no field for them by design).
 *
 * Google connects behind a GhostClean approval gate: the button requests
 * approval first, shows the pending state, and only starts the existing
 * OAuth flow after the backend reports APPROVED. Google's own consent
 * screen still applies in full afterward.
 */
export function ConnectionsPage(): React.JSX.Element {
  const { data, loading, error, reload } = useApi(() => oauthApi.connections());
  const approval = useApi(() => oauthApi.googleApprovalStatus());
  const [actionError, setActionError] = React.useState<unknown>(null);
  const [busyId, setBusyId] = React.useState<string | null>(null);
  const [googleBusy, setGoogleBusy] = React.useState(false);

  const startOAuth = async (provider: 'google' | 'microsoft'): Promise<void> => {
    // Backend owns the authorization URL; the frontend never constructs one.
    // NOTE: the backend only accepts a returnUrl on its own origin
    // (APP_BASE_URL). Pointing at the dev server will be rejected — serve
    // the app from the backend origin or adjust APP_BASE_URL for OAuth.
    const res = await oauthApi.authorize(provider);
    window.location.href = res.authorizationUrl;
  };

  const connectMicrosoft = async (): Promise<void> => {
    setActionError(null);
    setGoogleBusy(true);
    try {
      await startOAuth('microsoft');
    } catch (err) {
      setActionError(err);
    } finally {
      setGoogleBusy(false);
    }
  };

  /** Google entry point: approval first, OAuth only when the backend allows. */
  const connectGoogle = async (): Promise<void> => {
    setActionError(null);
    setGoogleBusy(true);
    try {
      const state: GoogleApprovalStatus = await oauthApi.requestGoogleApproval();
      if (state.status === 'APPROVED' || state.status === 'CONNECTED') {
        await startOAuth('google');
      } else {
        approval.reload();
      }
    } catch (err) {
      setActionError(err);
    } finally {
      setGoogleBusy(false);
    }
  };

  const cancelGoogleRequest = async (): Promise<void> => {
    setActionError(null);
    setGoogleBusy(true);
    try {
      await oauthApi.cancelGoogleApproval();
      approval.reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setGoogleBusy(false);
    }
  };

  const disconnect = async (id: string): Promise<void> => {
    if (!window.confirm('Disconnect this mailbox? Stored tokens are wiped immediately.')) return;
    setBusyId(id);
    setActionError(null);
    try {
      await oauthApi.disconnect(id);
      reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusyId(null);
    }
  };

  const refresh = async (id: string): Promise<void> => {
    setBusyId(id);
    setActionError(null);
    try {
      await oauthApi.refreshConnection(id);
      reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div>
      <h1>Connected Accounts</h1>
      <div className="alert" role="note">
        <p>
          <strong>GhostClean requests read-only mailbox access.</strong>
        </p>
        <p className="muted small" style={{ margin: 0 }}>
          We never send, modify, or delete your mail messages. Scopes stay minimal;
          insufficient or revoked access is reported — never silently bypassed.
        </p>
      </div>

      <div className="btn-row" style={{ marginBottom: '1rem' }}>
        <button type="button" className="btn btn-primary" onClick={connectGoogle} disabled={googleBusy}>
          {googleBusy ? 'Working…' : 'Connect Google'}
        </button>
        <button type="button" className="btn" onClick={connectMicrosoft} disabled={googleBusy}>
          Connect Microsoft
        </button>
      </div>

      {approval.data && approval.data.status !== 'NONE' && approval.data.status !== 'CONNECTED' && (
        <div
          className={approval.data.status === 'PENDING' ? 'alert alert-warning' : 'alert'}
          role="status"
        >
          {approval.data.status === 'PENDING' && (
            <>
              <p>
                <strong>Google connection approval requested.</strong>
              </p>
              <p className="small" style={{ margin: 0 }}>
                Status: PENDING — your request is waiting for administrator approval. Google
                OAuth cannot start until it is approved.
              </p>
              <div className="btn-row">
                <button type="button" className="btn" onClick={cancelGoogleRequest} disabled={googleBusy}>
                  Cancel request
                </button>
              </div>
            </>
          )}
          {approval.data.status === 'APPROVED' && (
            <>
              <p>
                <strong>Your Google connection request has been approved.</strong> Click
                “Connect Google” to start Google OAuth — you will still authorize your own
                Google account on Google's consent screen.
              </p>
              <p className="small" style={{ margin: 0 }}>
                One setup step remains in Google Cloud (GhostClean cannot do this
                automatically — Google offers no supported API for it): the Google
                account you will authorize must be listed under Audience → Test users
                in the Google Auth Platform console. Otherwise Google answers with
                “access denied”.
              </p>
            </>
          )}
          {approval.data.status === 'REJECTED' && (
            <p style={{ margin: 0 }}>
              <strong>Your Google connection request was not approved.</strong>{' '}
              {approval.data.rejectionReason ?? 'No reason was given.'} You may request
              again once the review cooldown passes.
            </p>
          )}
          {approval.data.status === 'CANCELLED' && (
            <p style={{ margin: 0 }}>
              The request was cancelled. Click “Connect Google” to request approval again.
            </p>
          )}
        </div>
      )}
      {approval.data?.status === 'CONNECTED' && (
        <div className="alert alert-success" role="status">
          <p style={{ margin: 0 }}>
            <strong>Google account connected.</strong>
          </p>
        </div>
      )}

      {Boolean(actionError) && (
        <div className="alert alert-danger" role="alert">
          <p>{friendlyErrorMessage(actionError)}</p>
        </div>
      )}

      {loading && <Loading label="Loading your connections" />}
      {Boolean(error) && <ErrorState error={error} onRetry={reload} />}
      {!loading && !error && (!data || data.length === 0) && (
        <EmptyState
          title="No mailbox connected yet."
          body="Connect Google or Microsoft to run your first read-only scan."
        />
      )}
      {!loading && !error && data && data.length > 0 && (
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th>Provider</th>
                <th>Account</th>
                <th>Status</th>
                <th>Scopes</th>
                <th>Connected</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {data.map((c) => (
                <tr key={c.id}>
                  <td data-label="Provider">{c.provider}</td>
                  <td data-label="Account" className="mono">
                    {c.providerEmailMasked}
                  </td>
                  <td data-label="Status">
                    <StatusBadge value={c.status} />
                  </td>
                  <td data-label="Scopes" className="small mono">
                    {c.scopes.length > 0 ? c.scopes.join(', ') : '—'}
                  </td>
                  <td data-label="Connected">{formatInstant(c.connectedAt)}</td>
                  <td data-label="Actions">
                    <div className="btn-row" style={{ marginTop: 0 }}>
                      <button
                        type="button"
                        className="btn"
                        disabled={busyId === c.id}
                        onClick={() => refresh(c.id)}
                      >
                        Refresh
                      </button>
                      <button
                        type="button"
                        className="btn btn-danger"
                        disabled={busyId === c.id}
                        onClick={() => disconnect(c.id)}
                      >
                        Disconnect
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
