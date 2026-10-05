package com.ghostclean;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghostclean.TestGmailApi.FakeGmailReader;
import com.ghostclean.account.DiscoveredServiceRepository;
import com.ghostclean.common.crypto.TokenCryptoProvider;
import com.ghostclean.detection.ServiceEvidenceRepository;
import com.ghostclean.oauth.OAuthConnection;
import com.ghostclean.oauth.OAuthConnectionRepository;
import com.ghostclean.scanner.MailScanRepository;
import com.ghostclean.services.ServiceDirectoryRepository;
import com.ghostclean.user.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Scan-test harness: fake Gmail provider, direct Google-connection seeding
 * (encrypted test tokens, documented scopes), and MockMvc scan helpers.
 * Every test uses fresh users/addresses so the shared rate limiter and
 * caches never leak state between tests.
 */
@AutoConfigureMockMvc
@Import({TestMailCapture.class, TestGoogleOAuth.class, TestGmailApi.class})
@ActiveProfiles({"test", "oauth-fake", "gmail-fake"})
public abstract class ScanTestBase extends AuthTestBase {

  protected static final String READ_SCOPE =
      "https://www.googleapis.com/auth/gmail.readonly";

  @Autowired
  protected FakeGmailReader fakeGmail;

  @Autowired
  protected OAuthConnectionRepository oauthConnections;

  @Autowired
  protected MailScanRepository scans;

  @Autowired
  protected DiscoveredServiceRepository discovered;

  @Autowired
  protected ServiceEvidenceRepository evidence;

  @Autowired
  protected ServiceDirectoryRepository directory;

  @Autowired
  protected TokenCryptoProvider crypto;

  @Autowired
  protected JdbcTemplate jdbc;

  /** Creates a verified user, logs in, and seeds an ACTIVE Google connection. */
  protected ScanFixture connectedUser(String tag) throws Exception {
    return connectedUser(tag, "ref-ok-" + tag, List.of("openid", "email", "profile", READ_SCOPE),
        OAuthConnection.STATUS_ACTIVE);
  }

  protected ScanFixture connectedUser(String tag, String refreshToken, List<String> scopes,
      String status) throws Exception {
    String email = "scan-" + tag + "-" + UUID.randomUUID() + "@example.com";
    verifiedUser(email, "Str0ng-Enough-Password");
    LoginTokens login = login(email, "Str0ng-Enough-Password", nextIp());
    User user = userRepository.findByEmail(email).orElseThrow();
    OAuthConnection connection = new OAuthConnection(user, "google", "sub-" + tag);
    connection.setProviderEmail("owner-" + tag + "@example.com");
    connection.setScopes(scopes.toArray(new String[0]));
    connection.setAccessTokenEnc(crypto.encrypt("acc-" + tag));
    if (refreshToken != null) {
      connection.setRefreshTokenEnc(crypto.encrypt(refreshToken));
    }
    connection.setKeyVersion(crypto.keyVersion());
    connection.setExpiresAt(Instant.now().plusSeconds(3600));
    connection.setStatus(status);
    connection.setLastRefreshedAt(Instant.now());
    oauthConnections.save(connection);
    fakeGmail.reset();
    return new ScanFixture(email, user.getId(), login.accessToken(), connection.getId());
  }

  protected MvcResult postScan(String accessToken, UUID connectionId, String ip) throws Exception {
    return mockMvc.perform(postJson("/api/v1/scans",
        Map.of("connectionId", connectionId.toString()), ip)
        .header("Authorization", "Bearer " + accessToken)).andReturn();
  }

  protected MvcResult postScan(String accessToken, UUID connectionId, String ip,
      String idempotencyKey) throws Exception {
    MockHttpServletRequestBuilder request =
        postJson("/api/v1/scans", Map.of("connectionId", connectionId.toString()), ip)
            .header("Authorization", "Bearer " + accessToken);
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    return mockMvc.perform(request).andReturn();
  }

  protected MockHttpServletRequestBuilder authedPost(String path, String accessToken) {
    return post(path).header("Authorization", "Bearer " + accessToken);
  }

  protected MockHttpServletRequestBuilder authedDelete(String path, String accessToken) {
    return delete(path).header("Authorization", "Bearer " + accessToken);
  }

  protected MockHttpServletRequestBuilder authedGetWithIp(String path, String accessToken,
      String ip) {
    MockHttpServletRequestBuilder request = get(path)
        .header("Authorization", "Bearer " + accessToken);
    request.with(r -> {
      r.setRemoteAddr(ip);
      return r;
    });
    return request;
  }

  protected JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  protected record ScanFixture(String email, UUID userId, String accessToken, UUID connectionId) {
  }
}
