import React from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { discoveryApi } from '../api/discoveryApi';
import { useApi } from '../hooks/useApi';
import { EmptyState, ErrorState, Loading, StatusBadge } from '../components/ui';
import { formatInstant } from '../utils/format';
import { friendlyErrorMessage } from '../utils/errors';
import { redactSecrets, truncate } from '../utils/sanitize';

/**
 * Discovery detail with safe evidence. Evidence subjects are truncated and
 * redacted as defense-in-depth; bodies are never returned by the backend and
 * have no UI surface here.
 */
export function DiscoveryDetailPage(): React.JSX.Element {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const account = useApi(() => discoveryApi.get(id ?? ''));
  const evidence = useApi(() => discoveryApi.evidence(id ?? ''));
  const [actionError, setActionError] = React.useState<unknown>(null);
  const [busy, setBusy] = React.useState(false);
  const [reason, setReason] = React.useState('');

  if (!id) {
    return (
      <div className="alert alert-danger" role="alert">
        <p>Missing discovery id.</p>
      </div>
    );
  }

  const review = async (decision: 'keep' | 'dismiss'): Promise<void> => {
    setBusy(true);
    setActionError(null);
    try {
      await discoveryApi.review(id, decision, reason || undefined);
      account.reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  const purge = async (): Promise<void> => {
    if (
      !window.confirm(
        'Delete all evidence for this account? The account is recomputed or removed. This cannot be undone.',
      )
    ) {
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      const res = await discoveryApi.purgeEvidence(id);
      if (res.accountRemoved) {
        navigate('/discoveries', { replace: true });
      } else {
        account.reload();
        evidence.reload();
      }
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <p>
        <Link to="/discoveries">← Back to discoveries</Link>
      </p>
      {account.loading && <Loading label="Loading discovery" />}
      {Boolean(account.error) && <ErrorState error={account.error} onRetry={account.reload} />}
      {!account.loading && !account.error && account.data && (
        <>
          <h1>{account.data.service}</h1>
          <div className="card">
            <dl className="kv">
              <dt>Domain</dt>
              <dd>{account.data.domain}</dd>
              <dt>Category</dt>
              <dd>{account.data.category}</dd>
              <dt>Status</dt>
              <dd>
                <StatusBadge value={account.data.status} />
              </dd>
              <dt>Confidence</dt>
              <dd>
                <StatusBadge value={account.data.confidence} /> ({account.data.confidenceScore})
              </dd>
              <dt>Evidence items</dt>
              <dd>{account.data.evidenceCount}</dd>
              <dt>First seen</dt>
              <dd>{formatInstant(account.data.firstSeenAt)}</dd>
              <dt>Last seen</dt>
              <dd>{formatInstant(account.data.lastSeenAt)}</dd>
              <dt>Route</dt>
              <dd>
                {account.data.privacyRoute.type} · {account.data.privacyRoute.availability} ·{' '}
                {account.data.privacyRoute.confidence}
                {account.data.privacyRoute.manualReviewRequired && ' · manual review required'}
              </dd>
            </dl>
            <p className="small muted">{account.data.privacyRoute.note}</p>
            {Boolean(actionError) && (
              <p className="field-error" role="alert">
                {friendlyErrorMessage(actionError)}
              </p>
            )}
            <div className="field" style={{ marginTop: '1rem' }}>
              <label htmlFor="dismiss-reason">Dismiss reason (optional)</label>
              <input
                id="dismiss-reason"
                type="text"
                maxLength={300}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="Why is this not yours?"
              />
            </div>
            <div className="btn-row">
              <button type="button" className="btn" disabled={busy} onClick={() => review('keep')}>
                Keep (mark reviewed)
              </button>
              <button type="button" className="btn" disabled={busy} onClick={() => review('dismiss')}>
                Dismiss
              </button>
              <button type="button" className="btn btn-danger" disabled={busy} onClick={purge}>
                Purge evidence
              </button>
              <Link className="btn btn-primary" to={`/deletion/preview?accountId=${account.data.id}`}>
                Prepare deletion request
              </Link>
            </div>
          </div>

          <h2 style={{ marginTop: '1.6rem' }}>Evidence (safe metadata only)</h2>
          <p className="small muted">
            Why was this detected? Each row shows the sender domain, a truncated subject,
            and the backend matching signals (rule ids and weight) behind the confidence
            score. Message bodies are never available here.
          </p>
          {evidence.loading && <Loading label="Loading evidence" />}
          {Boolean(evidence.error) && <ErrorState error={evidence.error} onRetry={evidence.reload} />}
          {!evidence.loading && !evidence.error && (!evidence.data || evidence.data.length === 0) && (
            <EmptyState title="No evidence rows." />
          )}
          {!evidence.loading && !evidence.error && evidence.data && evidence.data.length > 0 && (
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th>Source</th>
                    <th>Subject (truncated)</th>
                    <th>Why detected</th>
                    <th>Observed</th>
                  </tr>
                </thead>
                <tbody>
                  {evidence.data.map((row) => (
                    <tr key={row.id}>
                      <td data-label="Source" className="mono small">
                        {row.source} · {row.sourceDomain}
                      </td>
                      <td data-label="Subject">{truncate(redactSecrets(row.subject))}</td>
                      <td data-label="Why detected" className="small mono">
                        {row.matchedRules.length > 0 ? row.matchedRules.join(', ') : '—'}
                        <span className="muted"> (weight {row.weight})</span>
                      </td>
                      <td data-label="Observed">{formatInstant(row.observedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </div>
  );
}
