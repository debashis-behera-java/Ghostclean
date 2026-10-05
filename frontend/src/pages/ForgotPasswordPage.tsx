import React from 'react';
import { Link } from 'react-router-dom';
import { authApi } from '../api/authApi';
import { friendlyErrorMessage } from '../utils/errors';

export function ForgotPasswordPage(): React.JSX.Element {
  const [email, setEmail] = React.useState('');
  const [done, setDone] = React.useState(false);
  const [error, setError] = React.useState<unknown>(null);
  const [busy, setBusy] = React.useState(false);

  const submit = async (event: React.FormEvent): Promise<void> => {
    event.preventDefault();
    if (!email.trim()) {
      setError(new Error('Enter your email address.'));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await authApi.forgotPassword(email.trim());
      setDone(true);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="narrow">
      <h1>Forgot your password?</h1>
      {done ? (
        <div className="alert alert-success" role="status">
          <p>If an account exists for this email, a password reset link has been sent.</p>
          <p>
            <Link to="/login">Back to login</Link>
          </p>
        </div>
      ) : (
        <form onSubmit={submit} noValidate aria-label="Forgot password form">
          <div className="field">
            <label htmlFor="forgot-email">Email</label>
            <input
              id="forgot-email"
              type="email"
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </div>
          {Boolean(error) && (
            <p className="field-error" role="alert">
              {friendlyErrorMessage(error)}
            </p>
          )}
          <div className="btn-row">
            <button type="submit" className="btn btn-primary" disabled={busy}>
              {busy ? 'Sending…' : 'Send reset link'}
            </button>
          </div>
        </form>
      )}
    </main>
  );
}
