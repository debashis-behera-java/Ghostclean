package com.ghostclean.common;

import java.util.UUID;

/**
 * Authenticated principal: the user's id only. Controllers must derive
 * ownership from this — never from path variables, query params, or request
 * bodies (user-isolation foundation).
 */
public record AuthPrincipal(UUID userId) {
}
