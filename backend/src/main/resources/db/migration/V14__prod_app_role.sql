-- ============================================================================
-- GhostClean V14 — production application role (Phase 12, ADR-032)
-- ============================================================================
-- Creates the least-privilege runtime role. The role owns nothing and
-- receives only DML on existing tables plus sequence usage — no
-- SUPERUSER / CREATEDB / CREATEROLE / DDL. Migrations keep running as
-- the table owner (a separate privileged role in production).
--
-- Passwords are NEVER set here: local/test grants are sufficient for
-- the RLS matrix, and production assigns credentials (or IAM database
-- authentication) out of band via the secret manager. V13 RLS policies
-- are role-agnostic (`current_setting('app.user_id', true)`), so this
-- role is exactly the least-privilege path those policies enforce on.
--
-- CONVENTION (enforced by review, not by DDL): every future table
-- migration MUST repeat the GRANT block below for its new tables,
-- because ALTER DEFAULT PRIVILEGES cannot name the deployment-specific
-- owner role portably across local/test/RDS environments.
--
-- ROLLBACK: DROP OWNED BY ghostclean_app; DROP ROLE ghostclean_app;
-- (application code needs no change; the owner path keeps working).
-- ============================================================================

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'ghostclean_app') THEN
    CREATE ROLE ghostclean_app WITH
      NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
  END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO ghostclean_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ghostclean_app;

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ghostclean_app;
