import { Link, useParams } from 'react-router-dom';
import { serviceApi } from '../api/serviceApi';
import { useApi } from '../hooks/useApi';
import { EmptyState, ErrorState, Loading, StatusBadge } from '../components/ui';
import { formatInstant } from '../utils/format';

/** Explains what a deletion method means without overpromising. */
export function methodExplanation(method: string): string {
  switch ((method ?? '').toUpperCase()) {
    case 'EMAIL':
      return 'Eligible for explicit deletion-request dispatch when all backend safety gates pass. Approval and dispatch remain separate user actions.';
    case 'PRIVACY_PORTAL':
      return 'Manual action required — use the service\u2019s official privacy portal.';
    case 'ACCOUNT_SETTINGS':
      return 'Manual action required — use the service\u2019s account settings.';
    case 'WEB_FORM':
      return 'Manual action required — use the service\u2019s official web form.';
    default:
      return 'Manual review required. No verified route is known — GhostClean will not send anything automatically.';
  }
}

export function ServicesPage(): React.JSX.Element {
  const { data, loading, error, reload } = useApi(() => serviceApi.list());

  return (
    <div>
      <h1>Service Directory</h1>
      <div className="alert" role="note">
        <p className="small" style={{ margin: 0 }}>
          VERIFIED + KNOWN does <strong>not</strong> mean automatic deletion. Only verified
          email routes can ever be dispatched — and only after you approve the exact
          version and confirm dispatch separately. Everything else stays manual.
        </p>
      </div>
      {loading && <Loading label="Loading service directory" />}
      {Boolean(error) && <ErrorState error={error} onRetry={reload} />}
      {!loading && !error && (!data || data.length === 0) && (
        <EmptyState title="No services listed." />
      )}
      {!loading && !error && data && data.length > 0 && (
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th>Service</th>
                <th>Availability</th>
                <th>Automation</th>
                <th>Details</th>
              </tr>
            </thead>
            <tbody>
              {data.map((s) => (
                <tr key={s.serviceId}>
                  <td data-label="Service">
                    {s.displayName}
                    <div className="small muted">{s.category}</div>
                  </td>
                  <td data-label="Availability">
                    <StatusBadge value={s.routeAvailability} />
                  </td>
                  <td data-label="Automation">{s.automationStatus}</td>
                  <td data-label="Details">
                    <Link to={`/services/${encodeURIComponent(s.serviceId)}`}>View</Link>
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

export function ServiceDetailPage(): React.JSX.Element {
  const { slug } = useParams<{ slug: string }>();
  const { data, loading, error, reload } = useApi(() => serviceApi.get(slug ?? ''));

  return (
    <div>
      <p>
        <Link to="/services">← Back to directory</Link>
      </p>
      {loading && <Loading label="Loading service" />}
      {Boolean(error) && <ErrorState error={error} onRetry={reload} />}
      {!loading && !error && data && (
        <>
          <h1>{data.displayName}</h1>
          <div className="card">
            <dl className="kv">
              <dt>Route type</dt>
              <dd>{data.routeType}</dd>
              <dt>Verification</dt>
              <dd>
                <StatusBadge value={data.source.verificationStatus} />
              </dd>
              <dt>Availability</dt>
              <dd>
                <StatusBadge value={data.routeAvailability} />
              </dd>
              <dt>Confidence</dt>
              <dd>
                <StatusBadge value={data.routeConfidence} />
              </dd>
              <dt>Automation</dt>
              <dd>{data.automationStatus}</dd>
              <dt>Manual review</dt>
              <dd>{data.manualReviewRequired ? 'Required' : 'Not required by registry'}</dd>
              <dt>Last verified</dt>
              <dd>{formatInstant(data.lastVerifiedAt)}</dd>
            </dl>
            <p className="small muted">{data.routeNote}</p>
            <p className="small">{methodExplanation(data.routeType)}</p>
            {(data.privacyEmail || data.deletionUrl || data.privacyUrl || data.accountManagementUrl) && (
              <>
                <h3>Verified route references</h3>
                <p className="small muted">
                  Published by the registry for manual use. GhostClean never sends to these
                  on its own — dispatch remains a separate, explicit, backend-gated action
                  available only for eligible email routes.
                </p>
                <ul className="small">
                  {data.privacyEmail && <li>Privacy email: {data.privacyEmail}</li>}
                  {data.deletionUrl && (
                    <li>
                      Deletion page:{' '}
                      <a href={data.deletionUrl} target="_blank" rel="noreferrer">
                        {data.deletionUrl}
                      </a>
                    </li>
                  )}
                  {data.privacyUrl && (
                    <li>
                      Privacy page:{' '}
                      <a href={data.privacyUrl} target="_blank" rel="noreferrer">
                        {data.privacyUrl}
                      </a>
                    </li>
                  )}
                  {data.accountManagementUrl && (
                    <li>
                      Account management:{' '}
                      <a href={data.accountManagementUrl} target="_blank" rel="noreferrer">
                        {data.accountManagementUrl}
                      </a>
                    </li>
                  )}
                </ul>
              </>
            )}
            {data.routeAvailability !== 'KNOWN' && (
              <div className="alert alert-warning" role="note">
                <p className="small" style={{ margin: 0 }}>
                  GhostClean cannot safely automate this service yet. Any deletion request
                  stays manual — no automatic sending is possible.
                </p>
              </div>
            )}
            {data.source.url && (
              <p className="small">
                Source:{' '}
                <a href={data.source.url} target="_blank" rel="noreferrer">
                  {data.source.type} documentation
                </a>
              </p>
            )}
          </div>
        </>
      )}
    </div>
  );
}
