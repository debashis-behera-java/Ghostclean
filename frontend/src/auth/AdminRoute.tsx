import { Link } from 'react-router-dom';
import { useAuth } from './AuthContext';

/**
 * Admin-only route guard. Admin-ness comes from the backend profile
 * (verified owner of a configured admin address); the UI never decides it
 * locally and every admin API re-checks server-side (403 otherwise).
 */
export function AdminRoute({ children }: { children: React.JSX.Element }): React.JSX.Element {
  const { user, initializing } = useAuth();

  if (initializing) {
    return (
      <main className="center-page" aria-busy="true" aria-label="Checking session">
        <p>Checking your session…</p>
      </main>
    );
  }
  if (!user?.admin) {
    return (
      <main className="narrow">
        <h1>Not authorized</h1>
        <div className="alert alert-danger" role="alert">
          <p>Administrator access is required for this page.</p>
        </div>
        <p>
          <Link to="/dashboard">Back to dashboard</Link>
        </p>
      </main>
    );
  }
  return children;
}
