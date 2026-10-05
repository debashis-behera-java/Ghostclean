import React from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { authApi } from '../api/authApi';
import { friendlyErrorMessage } from '../utils/errors';

export function RegisterPage(): React.JSX.Element {
  const navigate = useNavigate();
  const [email, setEmail] = React.useState('');
  const [password, setPassword] = React.useState('');
  const [confirm, setConfirm] = React.useState('');
  const [error, setError] = React.useState<unknown>(null);
  const [busy, setBusy] = React.useState(false);

  const submit = async (event: React.FormEvent): Promise<void> => {
    event.preventDefault();
    if (!email.trim() || password.length < 12) {
      setError(new Error('Enter a valid email and a password of at least 12 characters.'));
      return;
    }
    if (password !== confirm) {
      setError(new Error('Passwords do not match.'));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await authApi.register(email.trim(), password);
      navigate(`/verify-pending?email=${encodeURIComponent(email.trim())}`, { replace: true });
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="narrow">
      <h1>Create your GhostClean account</h1>
      <form onSubmit={submit} noValidate aria-label="Registration form">
        <div className="field">
          <label htmlFor="reg-email">Email</label>
          <input
            id="reg-email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="reg-password">Password (minimum 12 characters)</label>
          <input
            id="reg-password"
            type="password"
            autoComplete="new-password"
            minLength={12}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="reg-confirm">Confirm password</label>
          <input
            id="reg-confirm"
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
            {busy ? 'Creating account…' : 'Register'}
          </button>
        </div>
      </form>
      <p className="small" style={{ marginTop: '1rem' }}>
        Already have an account? <Link to="/login">Log in</Link>
      </p>
    </main>
  );
}
