import React from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { authApi } from '../api/authApi';
import { friendlyErrorMessage } from '../utils/errors';

/**
 * Consumes POST /password/reset with ?token=. The token stays in component
 * state and the request body — it is never written to logs or localStorage.
 */
export function ResetPasswordPage(): React.JSX.Element {
  const [params] = useSearchParams();
  const token = params.get('token');
  const [password, setPassword] = React.useState('');
  const [confirm, setConfirm] = React.useState('');
  const [done, setDone] = React.useState(false);
  const [error, setError] = React.useState<unknown>(null);
  const [busy, setBusy] = React.useState(false);

  if (!token) {
    return (
      <main className="narrow">
        <h1>Reset your password</h1>
        <div className="alert alert-danger" role="alert">
          <p>
            <strong>This reset link is missing or invalid.</strong>
          </p>
          <p>
            <Link to="/forgot-password">Request a new password reset email</Link>
          </p>
        </div>
      </main>
    );
  }

  const submit = async (event: React.FormEvent): Promise<void> => {
    event.preventDefault();
    if (password.length < 12) {
      setError(new Error('Choose a password of at least 12 characters.'));
      return;
    }
    if (password !== confirm) {
      setError(new Error('Passwords do not match.'));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await authApi.resetPassword(token, password);
      setPassword('');
      setConfirm('');
      setDone(true);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="narrow">
      <h1>Choose a new password</h1>
      {done ? (
        <div className="alert alert-success" role="status">
          <p>
            <strong>Password reset successful.</strong>
          </p>
          <p>
            <Link to="/login">Log in with your new password</Link>
          </p>
        </div>
      ) : (
        <form onSubmit={submit} noValidate aria-label="Reset password form">
          <div className="field">
            <label htmlFor="reset-password">New password (minimum 12 characters)</label>
            <input
              id="reset-password"
              type="password"
              autoComplete="new-password"
              minLength={12}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </div>
          <div className="field">
            <label htmlFor="reset-confirm">Confirm new password</label>
            <input
              id="reset-confirm"
              type="password"
              autoComplete="new-password"
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
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
              {busy ? 'Resetting…' : 'Reset password'}
            </button>
          </div>
        </form>
      )}
    </main>
  );
}
