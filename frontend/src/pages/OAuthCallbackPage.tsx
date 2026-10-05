import { Link, useSearchParams } from 'react-router-dom';
import { codeToMessage } from '../utils/errors';

/**
 * Provider-callback landing page. The backend 302-redirects here with status
 * codes only (?provider=&status=&error=) — never tokens — so this page only
 * interprets those codes and never trusts URL parameters for ownership.
 */
export function OAuthCallbackPage(): React.JSX.Element {
  const [params] = useSearchParams();
  const provider = params.get('provider') ?? 'mailbox';
  const status = params.get('status');
  const errorCode = params.get('error');
  const connected = status === 'connected';

  return (
    <main className="narrow">
      <h1>Mailbox connection</h1>
      {connected ? (
        <div className="alert alert-success" role="status">
          <p>
            <strong>Your {provider} mailbox is connected.</strong>
          </p>
          <p>
            <Link to="/scan">Run a scan</Link> or <Link to="/connections">manage connections</Link>.
          </p>
        </div>
      ) : (
        <div className="alert alert-danger" role="alert">
          <p>
            <strong>The {provider} connection was not completed.</strong>
          </p>
          <p className="muted">{codeToMessage(errorCode)}</p>
          {(errorCode === 'OAUTH_DENIED' || errorCode === 'PROVIDER_ERROR') && (
            <p className="small">
              If Google answered “access denied”, the Google account you chose is not
              listed as a Test User: in the Google Auth Platform console open Audience
              → Test users and add that Google account, then try connecting again.
              GhostClean cannot add it for you — Google provides no supported API for
              that step.
            </p>
          )}
          <p>
            <Link to="/connections">Back to connections</Link>
          </p>
        </div>
      )}
    </main>
  );
}
