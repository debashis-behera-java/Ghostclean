import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { maskEmail, formatInstant } from '../utils/format';

export function ProfilePage(): React.JSX.Element {
  const { user } = useAuth();
  if (!user) return <p>Not signed in.</p>;
  return (
    <div>
      <h1>Profile</h1>
      <div className="card">
        <dl className="kv">
          <dt>Email</dt>
          <dd>{user.email}</dd>
          <dt>Email verified</dt>
          <dd>{user.emailVerified ? 'Yes' : 'No'}</dd>
          <dt>Status</dt>
          <dd>{user.status}</dd>
          <dt>Member since</dt>
          <dd>{formatInstant(user.createdAt)}</dd>
          <dt>Last login</dt>
          <dd>{formatInstant(user.lastLoginAt)}</dd>
        </dl>
      </div>
    </div>
  );
}

export function SettingsPage(): React.JSX.Element {
  const { user } = useAuth();
  return (
    <div>
      <h1>Settings</h1>
      <div className="card">
        <h2>Account</h2>
        <p className="small muted">
          Signed in as {user ? maskEmail(user.email) : '—'}. Password changes use the
          reset flow; sessions end with Log out.
        </p>
        <div className="btn-row">
          <Link className="btn" to="/forgot-password">
            Reset password
          </Link>
          <Link className="btn" to="/privacy">
            Privacy &amp; security
          </Link>
        </div>
      </div>
    </div>
  );
}
