package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.ghostclean.common.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Correlation-id rules without needing the Spring context. */
class RequestIdFilterTests {

  private final RequestIdFilter filter = new RequestIdFilter();

  @Test
  void generatesIdWhenAbsent() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertNotNull(response.getHeader(RequestIdFilter.HEADER));
    // MDC must be cleaned so ids never leak across requests/threads.
    assertNull(MDC.get(RequestIdFilter.MDC_KEY));
  }

  @Test
  void preservesInboundId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(RequestIdFilter.HEADER, "caller-supplied-id");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals("caller-supplied-id", response.getHeader(RequestIdFilter.HEADER));
  }

  @Test
  void replacesOverlongInboundId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(RequestIdFilter.HEADER, "x".repeat(512));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertNotNull(response.getHeader(RequestIdFilter.HEADER));
    assertNotEquals("x".repeat(512), response.getHeader(RequestIdFilter.HEADER));
  }
}
