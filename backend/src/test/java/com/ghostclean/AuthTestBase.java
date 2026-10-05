package com.ghostclean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghostclean.TestMailCapture.CapturingVerificationEmailSender;
import com.ghostclean.auth.JwtService;
import com.ghostclean.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared auth-test harness: MockMvc, mail capture, unique client IPs per
 * call (so the single-instance rate limiter never leaks state between
 * tests), and login helpers returning parsed tokens.
 */
@AutoConfigureMockMvc
@Import(TestMailCapture.class)
public abstract class AuthTestBase extends PostgresTestBase {

  private static final AtomicInteger IP_SEQ = new AtomicInteger(10);

  @Autowired
  protected MockMvc mockMvc;

  @Autowired
  protected CapturingVerificationEmailSender mailCapture;

  @Autowired
  protected JwtService jwtService;

  @Autowired
  protected UserRepository userRepository;

  @Autowired
  protected ObjectMapper objectMapper;

  /** POST with a distinct client IP (rate-limiter isolation between tests). */
  protected MockHttpServletRequestBuilder postJson(String path, Object body) {
    return postJson(path, body, nextIp());
  }

  protected MockHttpServletRequestBuilder postJson(String path, Object body, String ip) {
    try {
      MockHttpServletRequestBuilder request = post(path)
          .contentType(MediaType.APPLICATION_JSON)
          .content(objectMapper.writeValueAsString(body));
      request.with(r -> {
        r.setRemoteAddr(ip);
        return r;
      });
      return request;
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  protected String nextIp() {
    int n = IP_SEQ.getAndIncrement();
    return "10.20." + (n / 250) + "." + (1 + (n % 250));
  }

  protected void register(String email, String password, String ip) throws Exception {
    mockMvc.perform(postJson("/api/v1/auth/register",
        Map.of("email", email, "password", password), ip));
  }

  protected String registerAndCaptureToken(String email, String password) throws Exception {
    int before = mailCapture.count();
    register(email, password, nextIp());
    if (mailCapture.count() != before + 1) {
      throw new IllegalStateException("expected exactly one verification mail for " + email);
    }
    return mailCapture.latestToken();
  }

  protected void verify(String token, String ip) throws Exception {
    mockMvc.perform(postJson("/api/v1/auth/verify", Map.of("token", token), ip));
  }

  /** Registers, verifies, and returns the verified address (happy-path setup). */
  protected String verifiedUser(String email, String password) throws Exception {
    String token = registerAndCaptureToken(email, password);
    String ip = nextIp();
    mockMvc.perform(postJson("/api/v1/auth/verify", Map.of("token", token), ip));
    return email;
  }

  protected LoginTokens login(String email, String password, String ip) throws Exception {
    MvcResult result = mockMvc.perform(postJson("/api/v1/auth/login",
        Map.of("email", email, "password", password), ip)).andReturn();
    String body = result.getResponse().getContentAsString();
    JsonNode json = objectMapper.readTree(body);
    Cookie cookie = result.getResponse().getCookie("gc_refresh");
    return new LoginTokens(
        result.getResponse().getStatus(),
        json.has("accessToken") ? json.get("accessToken").asText() : null,
        json,
        cookie != null ? cookie.getValue() : null,
        result.getResponse().getHeader("Set-Cookie"));
  }

  protected MockHttpServletRequestBuilder authedGet(String path, String accessToken) {
    return get(path).header("Authorization", "Bearer " + accessToken);
  }

  protected record LoginTokens(int status, String accessToken, JsonNode body,
      String refreshCookie, String setCookieHeader) {
  }
}
