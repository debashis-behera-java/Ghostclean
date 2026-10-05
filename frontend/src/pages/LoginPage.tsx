import React from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { friendlyErrorMessage } from '../utils/errors';

export function LoginPage(): React.JSX.Element {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [email, setEmail] = React.useState('');
  const [password, setPassword] = React.useState('');
  const [error, setError] = React.useState<unknown>(null);
  const [busy, setBusy] = React.useState(false);

  const submit = async (event: React.FormEvent): Promise<void> => {
    event.preventDefault();
    if (!email.trim() || !password) {
      setError({ message: 'Enter your email and password.' });
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const profile = await login(email.trim(), password);
      if (!profile.emailVerified) {
        navigate(`/verify-pending?email=${encodeURIComponent(profile.email)}`, { replace: true });
        return;
      }
      const next = params.get('next');
      navigate(next && next.startsWith('/') ? next : '/dashboard', { replace: true });
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="narrow">
      <h1>Log in to GhostClean</h1>
      <form onSubmit={submit} noValidate aria-label="Login form">
        <div className="field">
          <label htmlFor="login-email">Email</label>
          <input
            id="login-email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="login-password">Password</label>
          <input
            id="login-password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </div>
        {Boolean(error) && (
          <p className="field-error" role="alert">
            {error instanceof Error && !('response' in (error as object))
              ? error.message
              : friendlyErrorMessage(error)}
          </p>
        )}
        <div className="btn-row">
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? 'Logging in…' : 'Log in'}
          </button>
        </div>
      </form>
      <p className="small" style={{ marginTop: '1rem' }}>
        <Link to="/forgot-password">Forgot your password?</Link>
      </p>
      <p className="small">
        No account yet? <Link to="/register">Register</Link>
      </p>
    </main>
  );
}
