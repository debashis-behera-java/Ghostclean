import React from 'react';
import { Link } from 'react-router-dom';
import { discoveryApi } from '../api/discoveryApi';
import { useApi } from '../hooks/useApi';
import { EmptyState, ErrorState, Loading, StatusBadge } from '../components/ui';
import { formatInstant } from '../utils/format';

export function DiscoveriesPage(): React.JSX.Element {
  const { data, loading, error, reload } = useApi(() => discoveryApi.list());
  const [query, setQuery] = React.useState('');
  const [confidence, setConfidence] = React.useState('');
  const [status, setStatus] = React.useState('');

  const filtered = (data ?? []).filter((a) => {
    if (confidence && a.confidence !== confidence) return false;
    if (status && a.status !== status) return false;
    if (query) {
      const q = query.toLowerCase();
      if (!`${a.service} ${a.domain}`.toLowerCase().includes(q)) return false;
    }
    return true;
  });

  return (
    <div>
      <h1>Discovered Accounts</h1>
      <p className="muted">
        Accounts detected from your mailbox metadata. Confidence and risk come from the
        backend — review each one before acting.
      </p>

      <div className="card" style={{ marginBottom: '1rem' }}>
        <div className="grid grid-3">
          <div className="field" style={{ marginBottom: 0 }}>
            <label htmlFor="disc-search">Search</label>
            <input
              id="disc-search"
              type="search"
              placeholder="Service or domain…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>
          <div className="field" style={{ marginBottom: 0 }}>
            <label htmlFor="disc-confidence">Confidence</label>
            <select id="disc-confidence" value={confidence} onChange={(e) => setConfidence(e.target.value)}>
              <option value="">All</option>
              <option value="HIGH">High</option>
              <option value="MEDIUM">Medium</option>
              <option value="LOW">Low</option>
            </select>
          </div>
          <div className="field" style={{ marginBottom: 0 }}>
            <label htmlFor="disc-status">Status</label>
            <select id="disc-status" value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="">All</option>
              <option value="DETECTED">Detected</option>
              <option value="REVIEWED">Reviewed</option>
              <option value="DISMISSED">Dismissed</option>
            </select>
          </div>
        </div>
      </div>

      {loading && <Loading label="Loading your discoveries" />}
      {Boolean(error) && <ErrorState error={error} onRetry={reload} />}
      {!loading && !error && (!data || data.length === 0) && (
        <EmptyState
          title="No accounts discovered yet. Run a scan to begin."
          body="Connect a mailbox and start a scan from the Scan page."
        />
      )}
      {!loading && !error && data && data.length > 0 && filtered.length === 0 && (
        <EmptyState title="No discoveries match these filters." />
      )}
      {!loading && !error && filtered.length > 0 && (
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th>Service</th>
                <th>Confidence</th>
                <th>Evidence</th>
                <th>Status</th>
                <th>Last seen</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((a) => (
                <tr key={a.id}>
                  <td data-label="Service">
                    <Link to={`/discoveries/${a.id}`}>{a.service}</Link>
                    <div className="small muted">{a.domain}</div>
                  </td>
                  <td data-label="Confidence">
                    <StatusBadge value={a.confidence} />
                  </td>
                  <td data-label="Evidence">{a.evidenceCount}</td>
                  <td data-label="Status">
                    <StatusBadge value={a.status} />
                  </td>
                  <td data-label="Last seen">{formatInstant(a.lastSeenAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
