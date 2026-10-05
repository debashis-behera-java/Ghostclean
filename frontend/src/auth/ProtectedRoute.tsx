import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from './AuthContext';

export function ProtectedRoute({ children }: { children: React.JSX.Element }): React.JSX.Element {
  const { user, initializing } = useAuth();
  const location = useLocation();

  if (initializing) {
    return (
      <main className="center-page" aria-busy="true" aria-label="Checking session">
        <p>Checking your session…</p>
      </main>
    );
  }
  if (!user) {
    const next = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?next=${next}`} replace />;
  }
  if (!user.emailVerified) {
    return <Navigate to={`/verify-pending?email=${encodeURIComponent(user.email)}`} replace />;
  }
  return children;
}
