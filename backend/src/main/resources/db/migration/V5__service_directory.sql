-- ============================================================================
-- GhostClean V5 — service directory table (Phase 4)
-- ============================================================================
-- Curated catalog for deterministic detection matching. Privacy/deletion
-- columns exist per SERVICE_DIRECTORY_DESIGN.md but stay NULL/UNKNOWN until
-- later phases verify them — nothing is fabricated. Seeds arrive in V7.
-- ============================================================================

CREATE TABLE service_directory (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  slug                  CITEXT NOT NULL,
  name                  TEXT NOT NULL,
  domains               TEXT[] NOT NULL DEFAULT '{}',
  aliases               TEXT[] NOT NULL DEFAULT '{}',
  category              VARCHAR(32) NOT NULL DEFAULT 'other',
  privacy_url           TEXT NULL,
  deletion_url          TEXT NULL,
  account_settings_url  TEXT NULL,
  privacy_email         CITEXT NULL,
  deletion_method       VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  gdpr_note             TEXT NULL,
  ccpa_note             TEXT NULL,
  api_deletion          BOOLEAN NOT NULL DEFAULT FALSE,
  manual_steps          TEXT NULL,
  last_verified_at      TIMESTAMPTZ NULL,
  version               INTEGER NOT NULL DEFAULT 1,
  status                VARCHAR(32) NOT NULL DEFAULT 'active',
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_service_directory_slug UNIQUE (slug),
  CONSTRAINT ck_service_directory_status CHECK (status IN ('active', 'deprecated'))
);

CREATE INDEX ix_dir_category ON service_directory (category);
