import { Link } from 'react-router-dom';

export function NotFoundPage(): React.JSX.Element {
  return (
    <main className="narrow">
      <h1>Page not found</h1>
      <p className="muted">The page you are looking for does not exist.</p>
      <div className="btn-row">
        <Link className="btn btn-primary" to="/dashboard">
          Go to dashboard
        </Link>
      </div>
    </main>
  );
}
