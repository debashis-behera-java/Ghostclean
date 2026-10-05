import { Link, useParams } from 'react-router-dom';
import { privacyRequestApi } from '../api/privacyRequestApi';
import { useApi } from '../hooks/useApi';
import { ErrorState, Loading, StatusBadge } from '../components/ui';
import { formatInstant } from '../utils/format';

export function TrackingPage(): React.JSX.Element {
  const { id } = useParams<{ id: string }>();
  const tracking = useApi(() => privacyRequestApi.tracking(id ?? ''));
  const dispatch = useApi(() => privacyRequestApi.dispatchStatus(id ?? ''));

  return (
    <div>
      <p>
        <Link to={id ? `/privacy-requests/${id}` : '/privacy-requests'}>← Back to request</Link>
      </p>
      <h1>Tracking</h1>
      {tracking.loading && <Loading label="Loading tracking" />}
      {Boolean(tracking.error) && <ErrorState error={tracking.error} onRetry={tracking.reload} />}
      {!tracking.loading && !tracking.error && tracking.data && (
        <div className="card">
          <dl className="kv">
            <dt>Request status</dt>
            <dd>
              <StatusBadge value={tracking.data.requestStatus} />
            </dd>
            <dt>Tracking state</dt>
            <dd>
              <StatusBadge value={tracking.data.trackingState} />
            </dd>
            <dt>Manual route</dt>
            <dd>{tracking.data.manualRoute ? 'Yes' : 'No'}</dd>
            <dt>Accepted at</dt>
            <dd>{formatInstant(tracking.data.acceptedAt)}</dd>
            <dt>Deadline (estimate)</dt>
            <dd>
              {formatInstant(tracking.data.deadlineAt)}
              <div className="small muted">{tracking.data.deadlineNote}</div>
            </dd>
            <dt>Reminders</dt>
            <dd>
              {tracking.data.reminderState}
              {tracking.data.nextReminderDue && ` · next ${formatInstant(tracking.data.nextReminderDue)}`}
            </dd>
            <dt>Latest outcome</dt>
            <dd>
              {tracking.data.latestOutcome ?? 'None recorded'}
              {tracking.data.latestSummary && ` — ${tracking.data.latestSummary}`}
            </dd>
            <dt>Responses</dt>
            <dd>{tracking.data.responseCount}</dd>
          </dl>
          <p className="small muted">
            Deadlines are estimates anchored at handoff — not proof of delivery, receipt,
            or deletion.
          </p>
        </div>
      )}
      <h2 style={{ marginTop: '1.6rem' }}>Dispatch result</h2>
      {dispatch.loading && <Loading label="Loading dispatch result" />}
      {Boolean(dispatch.error) && (
        <p className="muted small">No dispatch recorded for this request.</p>
      )}
      {!dispatch.loading && !dispatch.error && dispatch.data && (
        <div className="card">
          <dl className="kv">
            <dt>Status</dt>
            <dd>
              <StatusBadge value={dispatch.data.status} />
            </dd>
            <dt>Destination</dt>
            <dd>{dispatch.data.destination}</dd>
            <dt>Attempts</dt>
            <dd>{dispatch.data.attempts}</dd>
            <dt>Accepted at</dt>
            <dd>{formatInstant(dispatch.data.acceptedAt)}</dd>
            <dt>Failed at</dt>
            <dd>{formatInstant(dispatch.data.failedAt)}</dd>
          </dl>
        </div>
      )}
    </div>
  );
}
