import { Link } from 'react-router-dom';
import { discoveryApi } from '../api/discoveryApi';
import { oauthApi } from '../api/oauthApi';
import { privacyRequestApi } from '../api/privacyRequestApi';
import { scanApi } from '../api/scanApi';
import { useApi } from '../hooks/useApi';
import { EmptyState, ErrorState, Loading, StatusBadge } from '../components/ui';
import { formatInstant } from '../utils/format';

/**
 * Dashboard built exclusively from live backend data. Every number shown
 * comes from an API response; with no data the page says so explicitly.
 */
export function DashboardPage(): React.JSX.Element {
  const connections = useApi(() => oauthApi.connections());
  const scans = useApi(() => scanApi.list());
  const discoveries = useApi(() => discoveryApi.list());
  const requests = useApi(() => privacyRequestApi.list());

  if (connections.loading || scans.loading || discoveries.loading || requests.loading) {
    return <Loading label="Loading your dashboard" />;
  }
  const firstError =
    connections.error ?? scans.error ?? discoveries.error ?? requests.error;
  if (firstError) {
    return (
      <ErrorState
        error={firstError}
        onRetry={() => {
          connections.reload();
          scans.reload();
          discoveries.reload();
          requests.reload();
        }}
      />
    );
  }

  const mailboxCount = connections.data?.length ?? 0;
  const lastScan = scans.data?.[0] ?? null;
  const accounts = discoveries.data ?? [];
  const highConfidence = accounts.filter((a) => a.confidence === 'HIGH').length;
  const allRequests = requests.data ?? [];
  const countBy = (status: string): number => allRequests.filter((r) => r.status === status).length;
  const pendingRequests = allRequests.filter((r) =>
    ['DRAFT', 'READY_FOR_REVIEW', 'APPROVED'].includes(r.status),
  ).length;
  const manualReview = accounts.filter((a) => a.privacyRoute.manualReviewRequired).length;
  const activeConnection = (connections.data ?? []).find((c) => c.status === 'ACTIVE');

  return (
    <div>
      <h1>Dashboard</h1>
      {mailboxCount === 0 && (
        <div className="alert alert-warning" role="note">
          <p>
            <strong>No mailbox connected yet.</strong>
          </p>
          <p className="small" style={{ margin: 0 }}>
            <Link to="/connections">Connect Google or Microsoft</Link> to run your first
            read-only scan, then <Link to="/scan">start a scan</Link>.
          </p>
        </div>
      )}
      {mailboxCount > 0 && !lastScan && (
        <div className="alert" role="note">
          <p style={{ margin: 0 }}>
            Mailbox connected. <Link to="/scan">Run your first scan</Link> to discover
            accounts.
          </p>
        </div>
      )}
      <div className="grid grid-3">
        <div className="card">
          <div className="stat-label">Connected mailboxes</div>
          <div className="stat-value">{mailboxCount}</div>
          <span className="small muted">
            {activeConnection
              ? `Active: ${activeConnection.provider} connection ready`
              : 'No active connection'}
          </span>{' '}
          <Link to="/connections" className="small">
            Manage connections
          </Link>
        </div>
        <div className="card">
          <div className="stat-label">Last scan</div>
          <div className="stat-value" style={{ fontSize: '1.2rem' }}>
            {lastScan ? lastScan.status : 'No scan completed yet.'}
          </div>
          <span className="small muted">
            {lastScan ? formatInstant(lastScan.startedAt) : 'Run your first scan to begin.'}
          </span>
        </div>
        <div className="card">
          <div className="stat-label">Accounts detected</div>
          <div className="stat-value">{accounts.length}</div>
          <Link to="/discoveries" className="small">
            View discoveries
          </Link>
        </div>
        <div className="card">
          <div className="stat-label">High-confidence detections</div>
          <div className="stat-value">{highConfidence}</div>
        </div>
        <div className="card">
          <div className="stat-label">Pending deletion requests</div>
          <div className="stat-value">{pendingRequests}</div>
          <Link to="/privacy-requests" className="small">
            View requests
          </Link>
        </div>
        <div className="card">
          <div className="stat-label">Manual-review items</div>
          <div className="stat-value">{manualReview}</div>
          <p className="small muted" style={{ margin: 0 }}>
            Routes the backend reports as needing manual handling.
          </p>
        </div>
      </div>

      <h2 style={{ marginTop: '1.6rem' }}>Deletion requests by state</h2>
      {allRequests.length === 0 ? (
        <EmptyState
          title="No deletion requests yet."
          body="Preview a request from a discovery to begin. Approval and dispatch stay separate, explicit actions."
        />
      ) : (
        <div className="grid grid-3">
          {['DRAFT', 'READY_FOR_REVIEW', 'APPROVED', 'CANCELLED', 'EXPIRED'].map((s) => (
            <div className="card" key={s}>
              <div className="stat-label">{s.replace(/_/g, ' ')}</div>
              <div className="stat-value">{countBy(s)}</div>
            </div>
          ))}
          <div className="card">
            <div className="stat-label">Other states</div>
            <div className="stat-value">
              {allRequests.filter(
                (r) =>
                  !['DRAFT', 'READY_FOR_REVIEW', 'APPROVED', 'CANCELLED', 'EXPIRED'].includes(r.status),
              ).length}
            </div>
          </div>
        </div>
      )}

      <h2 style={{ marginTop: '1.6rem' }}>Recent discoveries</h2>
      {accounts.length === 0 ? (
        <EmptyState
          title="No accounts discovered yet. Run a scan to begin."
          body="Connect a mailbox, then start a scan from the Scan page."
        />
      ) : (
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th>Service</th>
                <th>Confidence</th>
                <th>Evidence</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {accounts.slice(0, 5).map((a) => (
                <tr key={a.id}>
                  <td data-label="Service">
                    <Link to={`/discoveries/${a.id}`}>{a.service}</Link>
                  </td>
                  <td data-label="Confidence">
                    <StatusBadge value={a.confidence} />
                  </td>
                  <td data-label="Evidence">{a.evidenceCount}</td>
                  <td data-label="Status">
                    <StatusBadge value={a.status} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <h2 style={{ marginTop: '1.6rem' }}>Security status</h2>
      <div className="card">
        <p className="small" style={{ margin: 0 }}>
          Mailbox access is read-only: GhostClean never sends, modifies, or deletes your
          messages. Deletion requests require your explicit approval followed by a
          separate explicit dispatch — approval alone never sends anything.
        </p>
      </div>
    </div>
  );
}
