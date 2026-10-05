import React from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { authApi } from '../api/authApi';
import { friendlyErrorMessage } from '../utils/errors';

/** Pending state with resend (backend always answers 202, no oracle). */
export function VerifyPendingPage(): React.JSX.Element {
  const [params] = useSearchParams();
  const email = params.get('email') ?? '';
  const [emailState, setEmailState] = React.useState(email);
  const [status, setStatus] = React.useState<string | null>(null);
  const [error, setError] = React.useState<unknown>(null);
  const [busy, setBusy] = React.useState(false);

  const resend = async (): Promise<void> => {
    if (!emailState.trim()) {
      setError(new Error('Enter the email address you registered with.'));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await authApi.resendVerification(emailState.trim());
      setStatus('If an unverified account exists for this email, a new verification email is on its way.');
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="narrow">
      <h1>Verify your email</h1>
      <p className="muted">
        We sent a verification email after registration. Open the newest link to activate
        your account, then log in.
      </p>
      <div className="field">
        <label htmlFor="resend-email">Email</label>
        <input
          id="resend-email"
          type="email"
          value={emailState}
          onChange={(e) => setEmailState(e.target.value)}
        />
      </div>
      {status && (
        <div className="alert alert-success" role="status">
          <p>{status}</p>
        </div>
      )}
      {Boolean(error) && (
        <p className="field-error" role="alert">
          {friendlyErrorMessage(error)}
        </p>
      )}
      <div className="btn-row">
        <button type="button" className="btn" onClick={resend} disabled={busy}>
          {busy ? 'Sending…' : 'Resend verification email'}
        </button>
        <Link className="btn btn-primary" to="/login">
          Back to login
        </Link>
      </div>
    </main>
  );
}

/** Handles POST /verify with a token (?token=) and links to backend result pages. */
export function VerifyResultPage(): React.JSX.Element {
  const [params] = useSearchParams();
  const token = params.get('token');
  const [state, setState] = React.useState<'idle' | 'working' | 'ok' | 'failed'>('idle');
  const [error, setError] = React.useState<unknown>(null);

  React.useEffect(() => {
    if (!token) {
      setState('failed');
      return;
    }
    setState('working');
    authApi
      .verify(token)
      .then(() => setState('ok'))
      .catch((err: unknown) => {
        setError(err);
        setState('failed');
      });
  }, [token]);

  return (
    <main className="narrow">
      <h1>Email verification</h1>
      {state === 'working' && <p role="status">Verifying…</p>}
      {state === 'ok' && (
        <div className="alert alert-success" role="status">
          <p>
            <strong>Your email is verified.</strong>
          </p>
          <p>
            You can now <Link to="/login">log in and connect your accounts</Link>.
          </p>
        </div>
      )}
      {state === 'failed' && (
        <div className="alert alert-danger" role="alert">
          <p>
            <strong>This link is invalid, expired, or already used.</strong>
          </p>
          <p className="muted">{token ? friendlyErrorMessage(error) : 'No verification token was provided.'}</p>
          <p>
            <Link to="/verify-pending">Request a new verification email</Link>
          </p>
        </div>
      )}
    </main>
  );
}
