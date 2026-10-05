/**
 * Privacy & security page. Claims mirror the backend privacy notice
 * (GET /api/v1/privacy) and implemented behavior only.
 */
export function PrivacyPage(): React.JSX.Element {
  return (
    <div>
      <h1>Privacy &amp; Security</h1>
      <div className="card">
        <h2>Read-only mailbox access</h2>
        <p className="small">
          GhostClean connects to mailboxes you own with read-only scopes. It never sends,
          modifies, or deletes your messages, and it stores metadata only — never email
          bodies or attachments.
        </p>
      </div>
      <div className="card">
        <h2>No automatic deletion</h2>
        <p className="small">
          Deletion requests require your explicit approval of the exact version, followed
          by a separate explicit dispatch confirmation. Approval alone never sends
          anything, and manual-review routes always stay manual.
        </p>
      </div>
      <div className="card">
        <h2>Ownership, audit, and safe failure</h2>
        <p className="small">
          Every record is owner-scoped: foreign ids read as not found. Security-relevant
          events are written to an audit trail. Credentials are encrypted at rest, and
          machine-learning scoring failures safely fall back to deterministic rules.
        </p>
      </div>
    </div>
  );
}
