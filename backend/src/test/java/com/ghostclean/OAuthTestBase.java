package com.ghostclean;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghostclean.TestGoogleOAuth.FakeGoogleOAuthClient;
import com.ghostclean.audit.AuditLogRepository;
import com.ghostclean.oauth.GoogleConnectionApprovalService;
import com.ghostclean.oauth.OAuthConnectionRepository;
import com.ghostclean.oauth.OAuthStateRepository;
import com.ghostclean.user.User;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * OAuth test harness: fake Google provider, synthetic Google client config,
 * and helpers for the authorize → callback → connection → disconnect cycle.
 */
@AutoConfigureMockMvc
@Import({TestMailCapture.class, TestGoogleOAuth.class})
@ActiveProfiles({"test", "oauth-fake"})
public abstract class OAuthTestBase extends AuthTestBase {

  @Autowired
  protected FakeGoogleOAuthClient fakeGoogle;

  @Autowired
  protected OAuthConnectionRepository connections;

  @Autowired
  protected OAuthStateRepository states;

  @Autowired
  protected AuditLogRepository audits;

  @Autowired
  protected GoogleConnectionApprovalService approvals;

  @DynamicPropertySource
  static void googleProps(DynamicPropertyRegistry registry) {
    registry.add("oauth.google.client-id", () -> "test-google-client-id");
    registry.add("oauth.google.client-secret", () -> "test-google-client-secret-synth");
    registry.add("oauth.google.redirect-uri",
        () -> "http://localhost:8080/api/v1/oauth/google/callback");
    registry.add("admin.emails", () -> "oauth-admin@example.com");
  }

  /** Verified user + GhostClean login, returning usable JWTs. */
  protected LoginTokens verifiedLogin(String email, String password) throws Exception {
    verifiedUser(email, password);
    return login(email, password, nextIp());
  }

  protected AuthorizeData authorize(String accessToken) throws Exception {
    return authorize(accessToken, null);
  }

  /**
   * Authorize after satisfying the Google approval gate: requests and
   * approves (as the test admin) on behalf of the token owner, then runs
   * the standard authorize call. Explicit at every call site — no hidden
   * approvals inside {@link #authorize(String)}.
   */
  protected AuthorizeData authorizeApproved(String accessToken) throws Exception {
    UUID userId = jwtService.parse(accessToken).userId();
    var request = approvals.request(userId);
    if ("PENDING".equals(request.status())) {
      approvals.approve(googleAdminId(), request.requestId());
    }
    return authorize(accessToken, null);
  }

  /** Test-admin identity (env-allowlisted); registered once, reused everywhere. */
  protected UUID googleAdminId() {
    return userRepository.findByEmail("oauth-admin@example.com")
        .map(User::getId)
        .orElseGet(() -> {
          try {
            verifiedUser("oauth-admin@example.com", "Str0ng-Enough-Password");
          } catch (Exception ex) {
            throw new IllegalStateException(ex);
          }
          return userRepository.findByEmail("oauth-admin@example.com").orElseThrow().getId();
        });
  }

  protected AuthorizeData authorize(String accessToken, String returnUrl) throws Exception {
    String path = "/api/v1/oauth/google/authorize"
        + (returnUrl == null ? "" : "?returnUrl=" + returnUrl);
    MvcResult result = mockMvc.perform(authedGet(path, accessToken)).andReturn();
    if (result.getResponse().getStatus() != 200) {
      throw new IllegalStateException("authorize failed: "
          + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString());
    }
    JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
    return new AuthorizeData(
        result.getResponse().getStatus(),
        json.has("authorizationUrl") ? json.get("authorizationUrl").asText() : null,
        json.has("state") ? json.get("state").asText() : null,
        result.getResponse().getContentAsString());
  }

  protected MvcResult callback(String code, String state) throws Exception {
    return callback("google", code, state, null);
  }

  protected MvcResult callback(String provider, String code, String state, String error)
      throws Exception {
    StringBuilder path = new StringBuilder("/api/v1/oauth/").append(provider).append("/callback?");
    boolean first = true;
    if (code != null) {
      path.append("code=").append(code);
      first = false;
    }
    if (state != null) {
      if (!first) {
        path.append('&');
      }
      path.append("state=").append(state);
      first = false;
    }
    if (error != null) {
      if (!first) {
        path.append('&');
      }
      path.append("error=").append(error);
    }
    return mockMvc.perform(get(path.toString())).andReturn();
  }

  protected static String locationOf(MvcResult result) {
    return result.getResponse().getHeader("Location");
  }

  protected static String stateFromUrl(String url) {
    for (String part : url.split("[?&]")) {
      if (part.startsWith("state=")) {
        return part.substring("state=".length());
      }
    }
    throw new IllegalStateException("no state in authorize URL: " + url);
  }

  protected record AuthorizeData(int status, String authorizationUrl, String state, String body) {
  }

  protected static String freshEmail(String tag) {
    return "oauth-" + tag + "-" + UUID.randomUUID() + "@example.com";
  }
}
