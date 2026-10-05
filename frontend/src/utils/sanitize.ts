/**
 * Defense-in-depth output sanitization. The backend already returns
 * truncated, sanitized evidence — the frontend additionally strips anything
 * shaped like a token, secret, or credential before rendering, and never
 * renders raw HTML from API payloads (React escapes by default; this helper
 * redacts known-dangerous patterns as text).
 */
const SECRET_PATTERNS: RegExp[] = [
  /acc-[A-Za-z0-9_-]+/g,
  /ref-[A-Za-z0-9_-]+/g,
  /Bearer\s+[A-Za-z0-9._~+/=-]+/gi,
  /sk-[A-Za-z0-9_-]{8,}/g,
  /xox[baprs]-[A-Za-z0-9-]+/g,
];

export function redactSecrets(text: string | null | undefined): string {
  if (!text) return '';
  let out = text;
  for (const pattern of SECRET_PATTERNS) {
    out = out.replace(pattern, '[redacted]');
  }
  return out;
}

/** Truncate long display strings without breaking layout. */
export function truncate(text: string, max = 160): string {
  if (text.length <= max) return text;
  return `${text.slice(0, max - 1)}…`;
}
