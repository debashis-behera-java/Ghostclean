/**
 * Shared kernel: error envelope, correlation IDs, crypto seams, global handlers.
 * Owns no business state. Every module may depend on {@code common}; it must
 * depend on none.
 */
package com.ghostclean.common;
