/**
 * User-controlled privacy-request workflow foundation (Phase 6).
 *
 * <p>Two-gate model: detection review (Phase 4) happens elsewhere; this
 * package owns the request lifecycle DRAFT → READY_FOR_REVIEW → APPROVED
 * plus terminal CANCELLED/EXPIRED. Generation is deterministic template
 * text — no AI, no network. Nothing here sends, submits, or deletes
 * anything externally; there are no SENT/SUBMITTED/DELETED states.
 */
package com.ghostclean.privacy;
