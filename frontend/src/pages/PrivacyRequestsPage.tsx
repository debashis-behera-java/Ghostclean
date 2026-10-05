import React from 'react';
import { Link } from 'react-router-dom';
import { privacyRequestApi } from '../api/privacyRequestApi';
import { useApi } from '../hooks/useApi';
import { EmptyState, ErrorState, Loading, StatusBadge } from '../components/ui';
import { formatInstant } from '../utils/format';

const STATUSES = ['DRAFT', 'READY_FOR_REVIEW', 'APPROVED', 'CANCELLED', 'EXPIRED'];

export function PrivacyRequestsPage(): React.JSX.Element {
  const [status, setStatus] = React.useState('');
  const { data, loading, error, reload } = useApi(() => privacyRequestApi.list(status || undefined));

  // Reload when the filter changes.
  React.useEffect(() => {
    reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status]);

  return (
    <div>
      <h1>Deletion Requests</h1>
      <p className="muted">
        Every request moves through review and approval before anything can be sent —
        and approval alone never sends. Dispatch is a separate, explicit confirmation.
      </p>
      <div className="card" style={{ marginBottom: '1rem' }}>
        <div className="field" style={{ marginBottom: 0, maxWidth: '320px' }}>
          <label htmlFor="req-status">Status filter</label>
          <select id="req-status" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">All</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
      </div>
      {loading && <Loading label="Loading your requests" />}
      {Boolean(error) && <ErrorState error={error} onRetry={reload} />}
      {!loading && !error && (!data || data.length === 0) && (
        <EmptyState
          title="No deletion requests yet."
          body="Preview a request from a discovery, then create it as a draft."
        />
      )}
      {!loading && !error && data && data.length > 0 && (
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th>Service</th>
                <th>Type</th>
                <th>Status</th>
                <th>Version</th>
                <th>Updated</th>
              </tr>
            </thead>
            <tbody>
              {data.map((r) => (
                <tr key={r.id}>
                  <td data-label="Service">
                    <Link to={`/privacy-requests/${r.id}`}>{r.service || r.domain}</Link>
                  </td>
                  <td data-label="Type" className="small">
                    {r.requestType}
                  </td>
                  <td data-label="Status">
                    <StatusBadge value={r.status} />
                  </td>
                  <td data-label="Version">
                    v{r.version}
                    {r.approvedVersion != null && (
                      <span className="small muted"> (approved v{r.approvedVersion})</span>
                    )}
                  </td>
                  <td data-label="Updated">{formatInstant(r.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
