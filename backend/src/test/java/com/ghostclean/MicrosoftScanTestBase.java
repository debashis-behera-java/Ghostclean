package com.ghostclean;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghostclean.TestGraphApi.FakeGraphReader;
import com.ghostclean.account.DiscoveredServiceRepository;
import com.ghostclean.common.crypto.TokenCryptoProvider;
import com.ghostclean.detection.ServiceEvidenceRepository;
import com.ghostclean.mailbox.GraphProperties;
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
 * Microsoft scan-test harness: fake Graph provider, direct
 * Microsoft-connection seeding (encrypted test tokens, delegated READ
 * scopes), and MockMvc scan helpers. Mirrors {@code ScanTestBase} (Google).
 * Every test uses fresh users/addresses so shared state never leaks.
 */
@AutoConfigureMockMvc
@Import({TestMailCapture.class, TestMicrosoftOAuth.class, TestGraphApi.class})
@ActiveProfiles({"test", "oauth-fake", "graph-fake"})
public abstract class MicrosoftScanTestBase extends AuthTestBase {

  protected static final String MAIL_READ_SCOPE = "https://graph.microsoft.com/Mail.Read";

  protected static final List<String> FULL_SCOPES = List.of(
      "openid", "profile", "offline_access", "User.Read", MAIL_READ_SCOPE);

  @Autowired
  protected FakeGraphReader fakeGraph;

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
  protected GraphProperties graph;

  @Autowired
  protected JdbcTemplate jdbc;

  /** Creates a verified user, logs in, and seeds an ACTIVE Microsoft connection. */
  protected ScanFixture connectedUser(String tag) throws Exception {
    return connectedUser(tag, "ref-ok-ms-" + tag, FULL_SCOPES, OAuthConnection.STATUS_ACTIVE);
  }

  protected ScanFixture connectedUser(String tag, String refreshToken, List<String> scopes,
      String status) throws Exception {
    String email = "ms-scan-" + tag + "-" + UUID.randomUUID() + "@example.com";
    verifiedUser(email, "Str0ng-Enough-Password");
    LoginTokens login = login(email, "Str0ng-Enough-Password", nextIp());
    User user = userRepository.findByEmail(email).orElseThrow();
    OAuthConnection connection = new OAuthConnection(user, "microsoft", "ms-id-" + tag);
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
    fakeGraph.reset();
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

  protected JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  protected record ScanFixture(String email, UUID userId, String accessToken, UUID connectionId) {
  }
}
