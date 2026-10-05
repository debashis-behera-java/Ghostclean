import React from 'react';
import { Link } from 'react-router-dom';
import { oauthApi } from '../api/oauthApi';
import { scanApi } from '../api/scanApi';
import { useApi } from '../hooks/useApi';
import { EmptyState, ErrorState, Loading, StatusBadge } from '../components/ui';
import { formatInstant } from '../utils/format';
import { friendlyErrorMessage } from '../utils/errors';

/**
 * Scan page. The backend executes scans synchronously and returns the
 * finished record, so the UI issues one request per scan — no background
 * polling loops. Cancel/delete act on explicit user clicks only.
 */
export function ScanPage(): React.JSX.Element {
  const connections = useApi(() => oauthApi.connections());
  const history = useApi(() => scanApi.list());
  const [connectionId, setConnectionId] = React.useState('');
  const [actionError, setActionError] = React.useState<unknown>(null);
  const [busy, setBusy] = React.useState(false);

  const start = async (): Promise<void> => {
    if (!connectionId) {
      setActionError(new Error('Choose a connected mailbox first.'));
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      await scanApi.start(connectionId, crypto.randomUUID());
      history.reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  const cancel = async (id: string): Promise<void> => {
    setBusy(true);
    setActionError(null);
    try {
      await scanApi.cancel(id);
      history.reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  const remove = async (id: string): Promise<void> => {
    if (!window.confirm('Delete this scan run record? Detected evidence is untouched.')) return;
    setBusy(true);
    setActionError(null);
    try {
      await scanApi.remove(id);
      history.reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <h1>Scan</h1>
      <div className="alert" role="note">
        <p className="small" style={{ margin: 0 }}>
          Scans read mailbox metadata only (sender domain, truncated subject, date).
          GhostClean never stores message bodies or attachments.
        </p>
      </div>

      {connections.loading && <Loading label="Loading your connections" />}
      {Boolean(connections.error) && <ErrorState error={connections.error} onRetry={connections.reload} />}
      {!connections.loading && !connections.error && (
        <div className="card">
          <h2>Start a scan</h2>
          {(connections.data?.length ?? 0) === 0 ? (
            <p className="muted">
              No mailbox connected yet. <Link to="/connections">Connect Google or Microsoft</Link>{' '}
              first.
            </p>
          ) : (
            <div className="field">
              <label htmlFor="scan-connection">Mailbox</label>
              <select
                id="scan-connection"
                value={connectionId}
                onChange={(e) => setConnectionId(e.target.value)}
              >
                <option value="">Choose a mailbox…</option>
                {connections.data?.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.provider} — {c.providerEmailMasked} ({c.status})
                  </option>
                ))}
              </select>
            </div>
          )}
          {Boolean(actionError) && (
            <p className="field-error" role="alert">
              {friendlyErrorMessage(actionError)}
            </p>
          )}
          <div className="btn-row">
            <button
              type="button"
              className="btn btn-primary"
              onClick={start}
              disabled={busy || !connectionId}
            >
              {busy ? 'Working…' : 'Start Scan'}
            </button>
          </div>
        </div>
      )}

      <h2 style={{ marginTop: '1.6rem' }}>Scan history</h2>
      {history.loading && <Loading label="Loading scan history" />}
      {Boolean(history.error) && <ErrorState error={history.error} onRetry={history.reload} />}
      {!history.loading && !history.error && (!history.data || history.data.length === 0) && (
        <EmptyState title="No scans yet." body="Start your first scan above." />
      )}
      {!history.loading && !history.error && history.data && history.data.length > 0 && (
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th>Started</th>
                <th>Status</th>
                <th>Messages</th>
                <th>Detected</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {history.data.map((s) => (
                <tr key={s.id}>
                  <td data-label="Started">{formatInstant(s.startedAt)}</td>
                  <td data-label="Status">
                    <StatusBadge value={s.status} />
                    {s.errorCode && <span className="small muted"> ({s.errorCode})</span>}
                  </td>
                  <td data-label="Messages">{s.messagesScanned}</td>
                  <td data-label="Detected">{s.candidatesDetected}</td>
                  <td data-label="Actions">
                    <div className="btn-row" style={{ marginTop: 0 }}>
                      {!['COMPLETED', 'FAILED', 'CANCELLED'].includes(s.status.toUpperCase()) && (
                        <button type="button" className="btn" disabled={busy} onClick={() => cancel(s.id)}>
                          Cancel scan
                        </button>
                      )}
                      <button type="button" className="btn" disabled={busy} onClick={() => remove(s.id)}>
                        Delete record
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
