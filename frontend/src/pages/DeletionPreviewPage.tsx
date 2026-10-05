import React from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { JURISDICTIONS, REQUEST_TYPES, deletionApi } from '../api/deletionApi';
import { discoveryApi } from '../api/discoveryApi';
import type { DeletionPreview } from '../types/api';
import { DryRunBanner, ErrorState, Loading, StatusBadge } from '../components/ui';
import { friendlyErrorMessage } from '../utils/errors';

/**
 * Deletion dry-run page. Preview renders without writing; "Create Request"
 * stores a draft only. Approval and dispatch live on the request page and
 * are never triggered from here.
 */
export function DeletionPreviewPage(): React.JSX.Element {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [accountId, setAccountId] = React.useState(params.get('accountId') ?? '');
  const [requestType, setRequestType] = React.useState('DELETE_PERSONAL_DATA');
  const [jurisdiction, setJurisdiction] = React.useState('GENERAL');
  const [preview, setPreview] = React.useState<DeletionPreview | null>(null);
  const [serviceName, setServiceName] = React.useState('');
  const [loading, setLoading] = React.useState(false);
  const [creating, setCreating] = React.useState(false);
  const [error, setError] = React.useState<unknown>(null);

  const runPreview = async (): Promise<void> => {
    if (!accountId.trim()) {
      setError(new Error('Choose a discovered account first.'));
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const [result, account] = await Promise.all([
        deletionApi.preview(accountId.trim(), requestType, jurisdiction),
        discoveryApi.get(accountId.trim()).catch(() => null),
      ]);
      setPreview(result);
      setServiceName(account?.service ?? result.service);
    } catch (err) {
      setPreview(null);
      setError(err);
    } finally {
      setLoading(false);
    }
  };

  const create = async (): Promise<void> => {
    if (!accountId.trim()) return;
    setCreating(true);
    setError(null);
    try {
      const created = await deletionApi.create(
        accountId.trim(),
        requestType,
        jurisdiction,
        crypto.randomUUID(),
      );
      navigate(`/privacy-requests/${created.id}`, { replace: true });
    } catch (err) {
      setError(err);
    } finally {
      setCreating(false);
    }
  };

  return (
    <div>
      <h1>Deletion Preview — Dry Run</h1>
      <DryRunBanner />
      <div className="card">
        <div className="grid grid-3">
          <div className="field">
            <label htmlFor="preview-account">Discovered account id</label>
            <input
              id="preview-account"
              type="text"
              value={accountId}
              onChange={(e) => setAccountId(e.target.value)}
              placeholder="Paste from Discoveries, or follow Prepare deletion request"
            />
          </div>
          <div className="field">
            <label htmlFor="preview-type">Request type</label>
            <select id="preview-type" value={requestType} onChange={(e) => setRequestType(e.target.value)}>
              {REQUEST_TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="preview-jurisdiction">Jurisdiction</label>
            <select
              id="preview-jurisdiction"
              value={jurisdiction}
              onChange={(e) => setJurisdiction(e.target.value)}
            >
              {JURISDICTIONS.map((j) => (
                <option key={j} value={j}>
                  {j}
                </option>
              ))}
            </select>
          </div>
        </div>
        <div className="btn-row">
          <button type="button" className="btn btn-primary" onClick={runPreview} disabled={loading}>
            {loading ? 'Loading preview…' : 'Show preview'}
          </button>
        </div>
      </div>

      {loading && <Loading label="Loading preview" />}
      {Boolean(error) && <ErrorState error={error} onRetry={runPreview} />}

      {preview && (
        <div className="card" style={{ marginTop: '1rem' }}>
          <h2>
            Preview for {serviceName || preview.service} <StatusBadge value={preview.deletionMethod} />
          </h2>
          <dl className="kv">
            <dt>Request type</dt>
            <dd>{preview.requestType}</dd>
            <dt>Destination</dt>
            <dd>{preview.destination ?? 'None — manual handling'}</dd>
            <dt>Manual review</dt>
            <dd>{preview.manualReviewRequired ? 'Required' : 'Not required by route'}</dd>
            <dt>Template</dt>
            <dd>
              {preview.templateId} v{preview.templateVersion}
            </dd>
          </dl>
          <h3>Generated content</h3>
          <p>
            <strong>{preview.subject}</strong>
          </p>
          <pre className="content">{preview.body}</pre>
          <h3>Warnings</h3>
          <ul className="small">
            {preview.warnings.map((w) => (
              <li key={w}>{w}</li>
            ))}
          </ul>
          {preview.manualInstructions.length > 0 && (
            <>
              <h3>Instructions</h3>
              <ul className="small">
                {preview.manualInstructions.map((line) => (
                  <li key={line}>{line}</li>
                ))}
              </ul>
            </>
          )}
          {Boolean(error) && (
            <p className="field-error" role="alert">
              {friendlyErrorMessage(error)}
            </p>
          )}
          <div className="btn-row">
            <button type="button" className="btn btn-primary" onClick={create} disabled={creating}>
              {creating ? 'Creating…' : 'Create Request (stores a draft only)'}
            </button>
            <Link className="btn" to="/discoveries">
              Back to discoveries
            </Link>
          </div>
        </div>
      )}
    </div>
  );
}
