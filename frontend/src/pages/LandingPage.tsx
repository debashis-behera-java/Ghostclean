import { Link } from 'react-router-dom';

export function LandingPage(): React.JSX.Element {
  return (
    <main>
      <header
        style={{
          borderBottom: '1px solid var(--border)',
          background: 'var(--bg-soft)',
        }}
      >
        <div
          className="main-inner"
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '0.9rem 1.6rem',
          }}
        >
          <span className="brand" style={{ border: 'none', padding: 0 }}>
            <span className="brand-mark" aria-hidden="true">
              G
            </span>
            GhostClean
          </span>
          <nav aria-label="Account" style={{ display: 'flex', gap: '0.6rem' }}>
            <Link className="btn" to="/login">
              Log in
            </Link>
            <Link className="btn btn-primary" to="/register">
              Get started
            </Link>
          </nav>
        </div>
      </header>

      <div className="main-inner" style={{ padding: '3rem 1.6rem' }}>
        <h1>See where your digital footprint exists — and safely remove abandoned accounts.</h1>
        <p className="muted" style={{ fontSize: '1.1rem', maxWidth: '640px' }}>
          GhostClean scans your mailbox (read-only) to find accounts you signed up for, then
          helps you prepare privacy and deletion requests — with explicit approval at every
          step. Nothing is ever deleted automatically.
        </p>
        <div className="btn-row">
          <Link className="btn btn-primary" to="/register">
            Scan My Accounts
          </Link>
          <Link className="btn" to="/login">
            Log in
          </Link>
        </div>

        <div className="grid grid-3" style={{ marginTop: '2.5rem' }}>
          <section className="card" aria-label="How it works">
            <h2>How it works</h2>
            <ol className="small">
              <li>Connect your mailbox with read-only access.</li>
              <li>Run a scan to discover accounts and evidence.</li>
              <li>Review each discovery and its confidence.</li>
              <li>Preview a deletion request (dry run).</li>
              <li>Approve the exact version, then explicitly dispatch it.</li>
            </ol>
          </section>
          <section className="card" aria-label="Read-only scanning">
            <h2>Read-only scanning</h2>
            <p className="small">
              GhostClean requests read-only mailbox access. It never sends, modifies, or
              deletes your messages, and it stores metadata only — never email bodies or
              attachments.
            </p>
          </section>
          <section className="card" aria-label="No automatic deletion">
            <h2>No automatic deletion</h2>
            <p className="small">
              Approval is not dispatch. A request is sent only after you approve its exact
              version <em>and</em> confirm dispatch separately. Manual-review routes stay
              manual — the app never claims otherwise.
            </p>
          </section>
        </div>

        <section className="card" style={{ marginTop: '1rem' }} aria-label="Providers">
          <h2>Supported providers</h2>
          <p className="small muted">
            Connect Google or Microsoft mailboxes you own. Scopes stay minimal
            (read-only); denied or restricted access is reported as a degraded manual
            mode, never silently bypassed.
          </p>
        </section>

        <footer className="muted small" style={{ marginTop: '3rem' }}>
          <p>
            GhostClean is a privacy engineering tool, not a law firm. Deletion outcomes
            depend on the receiving organization. <Link to="/login">Log in</Link> to
            continue.
          </p>
        </footer>
      </div>
    </main>
  );
}
