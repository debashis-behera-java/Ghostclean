-- ============================================================================
-- GhostClean V16 — deletion-route curation (research phase, 2026-10-02)
-- ============================================================================
-- Populates service_directory route columns ONLY where authoritative
-- evidence was documented in the deletion-route research phase:
--
--   microsoft   ACCOUNT_SETTINGS  (official support article, self-service
--               account closure; manual)
--   paypal      ACCOUNT_SETTINGS  (official help article, in-account
--               Data & Privacy erasure flow; manual)
--   resend      ACCOUNT_SETTINGS  (official docs, Profile Settings deletion;
--               manual)
--   outlier     EMAIL_REQUEST     (official privacy policy designates
--               privacy@outlier.ai for rights incl. deletion)
--   amazon-jobs EMAIL_REQUEST     (official hiring privacy notice instructs
--               deletion mail to hr-dsr-privacy@amazon.com; candidate-data
--               scope)
--   qualcomm    PRIVACY_REQUEST_FORM (official policy web form; the published
--               privacy@qualcomm.com address is for questions only, so it is
--               deliberately NOT recorded as a dispatch route)
--   capgemini   PRIVACY_REQUEST_FORM (official notice, DPO contact form)
--   wellfound   ACCOUNT_SETTINGS  (official help-center article, Settings
--               deletion; the conditional talent@wellfound.com fallback is
--               deliberately NOT recorded pending further verification)
--
-- Safety properties (enforced by existing gates, unchanged here):
-- - No scheduler, no auto-send: dispatch remains an explicit user action
--   (approve + explicit dispatch call), proven by NoDeletionActionTests.
-- - Only VERIFIED + KNOWN + EMAIL_REQUEST rows with a usable privacy_email
--   can pass DispatchService.requireEligible; every other row resolves to
--   manual review and 409s on dispatch.
-- - Uncertain services (TryHackMe, Aakash, edu.in, nic.in, and all other
--   seeds) are untouched and stay UNKNOWN/UNVERIFIED/manual-review.
-- - No discovered_services / user data is touched by this migration.
-- ============================================================================

-- Microsoft: verified self-service account-closure route (manual).
UPDATE service_directory
SET route_type = 'ACCOUNT_SETTINGS',
    request_method = 'ACCOUNT_SETTINGS',
    automation_status = 'MANUAL_ONLY',
    route_confidence = 'HIGH',
    route_availability = 'KNOWN',
    account_settings_url = 'https://account.microsoft.com',
    source_url = 'https://support.microsoft.com/en-us/accounts-billing/manage/how-to-close-your-microsoft-account',
    source_type = 'HELP_CENTER',
    verification_status = 'VERIFIED',
    manual_review_required = TRUE,
    last_verified_at = '2026-10-02T00:00:00+00:00',
    updated_at = now()
WHERE slug = 'microsoft';

-- Evidence-backed curated rows. Aliases stay empty: sender aliases are
-- identity evidence and are never invented; domain matching suffices.
-- Idempotent by slug (consistent with V7 seed style).
INSERT INTO service_directory
  (slug, name, domains, aliases, category,
   route_type, request_method, automation_status,
   route_confidence, route_availability,
   privacy_email, source_url, source_type, verification_status,
   manual_review_required, last_verified_at)
VALUES
  ('paypal', 'PayPal',
    '{paypal.com}', '{}', 'other',
    'ACCOUNT_SETTINGS', 'ACCOUNT_SETTINGS', 'MANUAL_ONLY',
    'HIGH', 'KNOWN',
    NULL,
    'https://www.paypal.com/us/cshelp/article/how-do-i-submit-a-data-erasure-request-help1036',
    'HELP_CENTER', 'VERIFIED',
    TRUE, '2026-10-02T00:00:00+00:00'),
  ('resend', 'Resend',
    '{resend.com}', '{}', 'other',
    'ACCOUNT_SETTINGS', 'ACCOUNT_SETTINGS', 'MANUAL_ONLY',
    'HIGH', 'KNOWN',
    NULL,
    'https://resend.com/docs/knowledge-base/how-can-i-delete-my-resend-account',
    'OFFICIAL', 'VERIFIED',
    TRUE, '2026-10-02T00:00:00+00:00'),
  ('outlier', 'Outlier',
    '{outlier.ai}', '{}', 'other',
    'EMAIL_REQUEST', 'EMAIL', 'MANUAL_ONLY',
    'HIGH', 'KNOWN',
    'privacy@outlier.ai',
    'https://outlier.ai/legal/privacy-policy',
    'OFFICIAL', 'VERIFIED',
    FALSE, '2026-10-02T00:00:00+00:00'),
  ('amazon-jobs', 'Amazon Jobs',
    '{amazon.jobs}', '{}', 'other',
    'EMAIL_REQUEST', 'EMAIL', 'MANUAL_ONLY',
    'HIGH', 'KNOWN',
    'hr-dsr-privacy@amazon.com',
    'https://hiring.amazon.com/privacy-notice',
    'OFFICIAL', 'VERIFIED',
    FALSE, '2026-10-02T00:00:00+00:00'),
  ('qualcomm', 'Qualcomm',
    '{qualcomm.com}', '{}', 'other',
    'PRIVACY_REQUEST_FORM', 'WEB_FORM', 'MANUAL_ONLY',
    'MEDIUM', 'KNOWN',
    NULL,
    'https://www.qualcomm.com/site/privacy',
    'OFFICIAL', 'VERIFIED',
    TRUE, '2026-10-02T00:00:00+00:00'),
  ('capgemini', 'Capgemini',
    '{capgemini.com}', '{}', 'other',
    'PRIVACY_REQUEST_FORM', 'WEB_FORM', 'MANUAL_ONLY',
    'MEDIUM', 'KNOWN',
    NULL,
    'https://www.capgemini.com/privacy-notice/',
    'OFFICIAL', 'VERIFIED',
    TRUE, '2026-10-02T00:00:00+00:00'),
  ('wellfound', 'Wellfound',
    '{wellfound.com}', '{}', 'other',
    'ACCOUNT_SETTINGS', 'ACCOUNT_SETTINGS', 'MANUAL_ONLY',
    'HIGH', 'KNOWN',
    NULL,
    'https://help.wellfound.com/article/808-delete-user-profile',
    'HELP_CENTER', 'VERIFIED',
    TRUE, '2026-10-02T00:00:00+00:00')
ON CONFLICT ON CONSTRAINT uq_service_directory_slug DO NOTHING;
