-- ============================================================================
-- GhostClean V7 — service directory seed (Phase 4)
-- ============================================================================
-- Small curated foundation for deterministic detection matching: well-known
-- service domains and sender aliases (public facts, not PII). Privacy and
-- deletion columns intentionally stay NULL/UNKNOWN — later phases verify
-- and populate them; nothing here is fabricated. Idempotent by slug.
-- ============================================================================

INSERT INTO service_directory (slug, name, domains, aliases, category)
VALUES
  ('spotify', 'Spotify',
    '{spotify.com}',
    '{no-reply@spotify.com,welcome@spotify.com,account@spotify.com}',
    'media'),
  ('netflix', 'Netflix',
    '{netflix.com}',
    '{info@netflix.com,account@netflix.com}',
    'media'),
  ('github', 'GitHub',
    '{github.com}',
    '{noreply@github.com,notifications@github.com}',
    'dev'),
  ('amazon', 'Amazon',
    '{amazon.com,amazon.co.uk,amazon.de}',
    '{auto-confirm@amazon.com,account-update@amazon.com}',
    'shopping'),
  ('google', 'Google',
    '{google.com,accounts.google.com}',
    '{no-reply@accounts.google.com}',
    'other'),
  ('apple', 'Apple',
    '{apple.com,icloud.com}',
    '{no_reply@email.apple.com,appleid@id.apple.com}',
    'other'),
  ('microsoft', 'Microsoft',
    '{microsoft.com,outlook.com,hotmail.com,live.com}',
    '{account-security-noreply@accountprotection.microsoft.com}',
    'other'),
  ('dropbox', 'Dropbox',
    '{dropbox.com}',
    '{no-reply@dropbox.com}',
    'other')
ON CONFLICT ON CONSTRAINT uq_service_directory_slug DO NOTHING;
