package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ghostclean.common.ApiError;
import com.ghostclean.common.GlobalExceptionHandler;
import com.ghostclean.common.RequestIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Error semantics: security rejects anonymous callers before routing (401,
 * no oracle), while the handler itself always emits the stable envelope —
 * never stack traces or internal messages.
 */
@AutoConfigureMockMvc
class GlobalErrorHandlingTests extends PostgresTestBase {

  @Autowired
  private MockMvc mockMvc;

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @AfterEach
  void clearMdc() {
    MDC.remove(RequestIdFilter.MDC_KEY);
  }

  @Test
  void anonymousUnknownRouteIsRejectedBeforeRouting() throws Exception {
    // Secure-by-default ordering: authentication precedes resource resolution,
    // so anonymous callers learn nothing about route existence.
    mockMvc.perform(get("/api/v1/does-not-exist"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists("X-Request-Id"));
  }

  @Test
  void notFoundEnvelope() {
    MockHttpServletRequest request = requestWithId("/api/v1/does-not-exist", "req-404");
    ResponseEntity<ApiError> response =
        handler.handleNotFound(new NoResourceFoundException(HttpMethod.GET, "/api/v1/does-not-exist"),
            request);
    assertEquals(404, response.getStatusCode().value());
    assertNotNull(response.getBody());
    assertEquals("NOT_FOUND", response.getBody().code());
    assertEquals("/api/v1/does-not-exist", response.getBody().path());
    assertEquals("req-404", response.getBody().requestId());
    assertNotNull(response.getBody().timestamp());
  }

  @Test
  void forbiddenEnvelope() {
    MockHttpServletRequest request = requestWithId("/api/v1/users/me", "req-403");
    ResponseEntity<ApiError> response =
        handler.handleForbidden(new AccessDeniedException("denied"), request);
    assertEquals(403, response.getStatusCode().value());
    assertEquals("FORBIDDEN", response.getBody().code());
  }

  @Test
  void unexpectedErrorsStayGeneric() {
    MockHttpServletRequest request = requestWithId("/api/v1/health", "req-500");
    ResponseEntity<ApiError> response =
        handler.handleUnexpected(new IllegalStateException("db password=hunter2"), request);
    assertEquals(500, response.getStatusCode().value());
    assertEquals("Unexpected error", response.getBody().message());
  }

  private MockHttpServletRequest requestWithId(String uri, String requestId) {
    MDC.put(RequestIdFilter.MDC_KEY, requestId);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
    request.setRequestURI(uri);
    return request;
  }
}
