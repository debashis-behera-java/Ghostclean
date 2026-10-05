package com.ghostclean.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Correlation-ID filter. Accepts an inbound {@code X-Request-Id} (bounded to
 * 128 chars) or generates one, exposes it to logs via MDC, and echoes it back
 * on the response. The id itself must never contain sensitive data.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Request-Id";
  public static final String MDC_KEY = "requestId";
  private static final int MAX_INBOUND_LENGTH = 128;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestId = incomingOrGenerated(request.getHeader(HEADER));
    MDC.put(MDC_KEY, requestId);
    try {
      response.setHeader(HEADER, requestId);
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  static String incomingOrGenerated(String incoming) {
    if (incoming != null && !incoming.isBlank() && incoming.length() <= MAX_INBOUND_LENGTH) {
      return incoming;
    }
    return UUID.randomUUID().toString();
  }
}
