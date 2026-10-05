package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghostclean.oauth.GoogleConnectionRequest;
import com.ghostclean.oauth.GoogleConnectionRequestRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Google connection approval (GhostClean business gate before OAuth).
 * Synthetic users only; the fake Google provider is never contacted except
 * through the standard authorize/callback cycle. Proves the request
 * lifecycle, admin-only review, the OAuth authorize gate, and secret-free
 * responses — without weakening any existing control.
 */
class GoogleConnectionApprovalTests extends OAuthTestBase {

  @Autowired
  private GoogleConnectionRequestRepository approvalRequests;

  private MockHttpServletRequestBuilder authedPost(String path, String accessToken) {
    return post(path).header("Authorization", "Bearer " + accessToken);
  }

  private LoginTokens adminLogin() throws Exception {
    googleAdminId();
    return login("oauth-admin@example.com", "Str0ng-Enough-Password", nextIp());
  }

  private UUID userIdOf(LoginTokens tokens) {
    return jwtService.parse(tokens.accessToken()).userId();
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  // -- user lifecycle ----------------------------------------------------------

  @Test
  void userCanCreateRequestAndAdminIsNotified() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("gcreq"), "Str0ng-Enough-Password");
    int noticesBefore = mailCapture.adminNotices().size();

    MvcResult result = mockMvc.perform(authedPost(
        "/api/v1/google/connection/request", tokens.accessToken())).andReturn();
    assertEquals(200, result.getResponse().getStatus());
    JsonNode dto = json(result);
    assertEquals("PENDING", dto.get("status").asText());
    assertFalse(dto.get("requestId").asText().isBlank());

    var notices = mailCapture.adminNotices();
    assertEquals(noticesBefore + 1, notices.size());
    var notice = notices.get(notices.size() - 1);
    assertEquals("oauth-admin@example.com", notice.to());
    assertTrue(notice.subject().contains("Google connection request"));
    String serialized = objectMapper.writeValueAsString(notice);
    assertFalse(serialized.contains("acc-"));
    assertFalse(serialized.contains("ref-"));
    assertFalse(serialized.toLowerCase().contains("token"));
  }

  @Test
  void duplicateRequestIsNotCreated() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("gcdup"), "Str0ng-Enough-Password");
    String first = json(mockMvc.perform(authedPost(
        "/api/v1/google/connection/request", tokens.accessToken())).andReturn())
        .get("requestId").asText();
    String second = json(mockMvc.perform(authedPost(
        "/api/v1/google/connection/request", tokens.accessToken())).andReturn())
        .get("requestId").asText();
    assertEquals(first, second);
    assertEquals(1, approvalRequests.findByUser_IdOrderByRequestedAtDesc(userIdOf(tokens)).size());
  }

  @Test
  void statusLifecycleNonePendingApproved() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("gcstat"), "Str0ng-Enough-Password");
    JsonNode none = json(mockMvc.perform(
        authedGet("/api/v1/google/connection/status", tokens.accessToken())).andReturn());
    assertEquals("NONE", none.get("status").asText());

    UUID requestId = UUID.fromString(json(mockMvc.perform(authedPost(
        "/api/v1/google/connection/request", tokens.accessToken())).andReturn())
        .get("requestId").asText());
    JsonNode pending = json(mockMvc.perform(
        authedGet("/api/v1/google/connection/status", tokens.accessToken())).andReturn());
    assertEquals("PENDING", pending.get("status").asText());

    LoginTokens admin = adminLogin();
    mockMvc.perform(authedPost(
        "/api/v1/admin/google/connection-requests/" + requestId + "/approve",
        admin.accessToken())).andReturn();
    JsonNode approved = json(mockMvc.perform(
        authedGet("/api/v1/google/connection/status", tokens.accessToken())).andReturn());
    assertEquals("APPROVED", approved.get("status").asText());
  }

  @Test
  void rejectedRequestHonorsCooldownThenAllowsRetry() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("gcrej"), "Str0ng-Enough-Password");
    UUID requestId = UUID.fromString(json(mockMvc.perform(authedPost(
        "/api/v1/google/connection/request", tokens.accessToken())).andReturn())
        .get("requestId").asText());
    LoginTokens admin = adminLogin();

    MvcResult rejected = mockMvc.perform(authedPost(
        "/api/v1/admin/google/connection-requests/" + requestId + "/reject",
        admin.accessToken())
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(java.util.Map.of("reason", "not yet"))))
        .andReturn();
    assertEquals(200, rejected.getResponse().getStatus());
    assertEquals("REJECTED", json(rejected).get("status").asText());
    assertEquals("not yet", json(rejected).get("rejectionReason").asText());

    MvcResult tooSoon = mockMvc.perform(authedPost(
        "/api/v1/google/connection/request", tokens.accessToken())).andReturn();
    assertEquals(409, tooSoon.getResponse().getStatus());

    GoogleConnectionRequest row = approvalRequests.findById(requestId).orElseThrow();
    row.setReviewedAt(Instant.now().minusSeconds(25 * 3600));
    approvalRequests.saveAndFlush(row);

    MvcResult retry = mockMvc.perform(authedPost(
        "/api/v1/google/connection/request", tokens.accessToken())).andReturn();
    assertEquals(200, retry.getResponse().getStatus());
    assertEquals("PENDING", json(retry).get("status").asText());
  }

  @Test
  void ownerCanCancelPendingThenRequestAgain() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("gccancel"), "Str0ng-Enough-Password");
    mockMvc.perform(authedPost(
        "/api/v1/google/connection/request", tokens.accessToken())).andReturn();
    MvcResult cancelled = mockMvc.perform(authedPost(
        "/api/v1/google/connection/request/cancel", tokens.accessToken())).andReturn();
    assertEquals(200, cancelled.getResponse().getStatus());
    assertEquals("CANCELLED", json(cancelled).get("status").asText());

    MvcResult again = mockMvc.perform(authedPost(
        "/api/v1/google/connection/request", tokens.accessToken())).andReturn();
    assertEquals(200, again.getResponse().getStatus());
    assertEquals("PENDING", json(again).get("status").asText());
  }

  @Test
  void unauthenticatedRequestIsRejected() throws Exception {
    mockMvc.perform(post("/api/v1/google/connection/request"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .status().isUnauthorized());
  }

  // -- admin authorization -------------------------------------------------------

  @Test
  void normalUserCannotAccessAdminEndpoints() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("noadmin"), "Str0ng-Enough-Password");
    UUID foreign = UUID.randomUUID();
    assertEquals(403, mockMvc.perform(
        authedGet("/api/v1/admin/google/connection-requests", tokens.accessToken()))
        .andReturn().getResponse().getStatus());
    assertEquals(403, mockMvc.perform(
        authedPost("/api/v1/admin/google/connection-requests/" + foreign + "/approve",
            tokens.accessToken())).andReturn().getResponse().getStatus());
    assertEquals(403, mockMvc.perform(
        authedPost("/api/v1/admin/google/connection-requests/" + foreign + "/reject",
            tokens.accessToken())).andReturn().getResponse().getStatus());
  }

  @Test
  void userCannotApproveTheirOwnRequest() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("selfappr"), "Str0ng-Enough-Password");
    UUID requestId = UUID.fromString(json(mockMvc.perform(authedPost(
        "/api/v1/google/connection/request", tokens.accessToken())).andReturn())
        .get("requestId").asText());
    // Non-admin owner attempting the admin action: 403, request untouched.
    assertEquals(403, mockMvc.perform(authedPost(
        "/api/v1/admin/google/connection-requests/" + requestId + "/approve",
        tokens.accessToken())).andReturn().getResponse().getStatus());
    JsonNode status = json(mockMvc.perform(
        authedGet("/api/v1/google/connection/status", tokens.accessToken())).andReturn());
    assertEquals("PENDING", status.get("status").asText());
  }

  @Test
  void usersCannotSeeEachOthersRequests() throws Exception {
    LoginTokens owner = verifiedLogin(freshEmail("gcowner"), "Str0ng-Enough-Password");
    LoginTokens other = verifiedLogin(freshEmail("gcother"), "Str0ng-Enough-Password");
    mockMvc.perform(authedPost(
        "/api/v1/google/connection/request", owner.accessToken())).andReturn();

    JsonNode otherStatus = json(mockMvc.perform(
        authedGet("/api/v1/google/connection/status", other.accessToken())).andReturn());
    assertEquals("NONE", otherStatus.get("status").asText());
    MvcResult cancelForeign = mockMvc.perform(authedPost(
        "/api/v1/google/connection/request/cancel", other.accessToken())).andReturn();
    assertEquals(409, cancelForeign.getResponse().getStatus());
  }

  @Test
  void adminCanListApproveAndReject() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("gclist"), "Str0ng-Enough-Password");
    mockMvc.perform(authedPost(
        "/api/v1/google/connection/request", tokens.accessToken())).andReturn();
    LoginTokens admin = adminLogin();

    JsonNode list = json(mockMvc.perform(
        authedGet("/api/v1/admin/google/connection-requests?status=PENDING",
            admin.accessToken())).andReturn());
    assertTrue(list.isArray() && list.size() >= 1);
    boolean found = false;
    UUID target = null;
    for (JsonNode row : list) {
      if (tokens.body() != null && row.get("userEmail").asText().contains("gclist")) {
        found = true;
        target = UUID.fromString(row.get("requestId").asText());
      }
    }
    assertTrue(found, "admin queue must include the requester email");
    String serialized = objectMapper.writeValueAsString(list);
    assertFalse(serialized.contains("acc-"));
    assertFalse(serialized.contains("ref-"));
    assertFalse(serialized.toLowerCase().contains("bearer"));

    MvcResult approved = mockMvc.perform(authedPost(
        "/api/v1/admin/google/connection-requests/" + target + "/approve",
        admin.accessToken())).andReturn();
    assertEquals(200, approved.getResponse().getStatus());
    assertEquals("APPROVED", json(approved).get("status").asText());

    MvcResult repeat = mockMvc.perform(authedPost(
        "/api/v1/admin/google/connection-requests/" + target + "/approve",
        admin.accessToken())).andReturn();
    assertEquals(409, repeat.getResponse().getStatus());
  }

  @Test
  void adminActionOnUnknownRequestIsNotFound() throws Exception {
    LoginTokens admin = adminLogin();
    assertEquals(404, mockMvc.perform(authedPost(
        "/api/v1/admin/google/connection-requests/" + UUID.randomUUID() + "/approve",
        admin.accessToken())).andReturn().getResponse().getStatus());
  }

  // -- OAuth gate ------------------------------------------------------------------

  @Test
  void authorizeIsBlockedWhilePendingOrMissing() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("gcgate"), "Str0ng-Enough-Password");
    MvcResult none = mockMvc.perform(
        authedGet("/api/v1/oauth/google/authorize", tokens.accessToken())).andReturn();
    assertEquals(409, none.getResponse().getStatus());
    assertTrue(none.getResponse().getContentAsString().contains("REQUEST_CONFLICT"));

    mockMvc.perform(authedPost(
        "/api/v1/google/connection/request", tokens.accessToken())).andReturn();
    MvcResult pending = mockMvc.perform(
        authedGet("/api/v1/oauth/google/authorize", tokens.accessToken())).andReturn();
    assertEquals(409, pending.getResponse().getStatus());
  }

  @Test
  void authorizeSucceedsAfterApproval() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("gcok"), "Str0ng-Enough-Password");
    UUID requestId = UUID.fromString(json(mockMvc.perform(authedPost(
        "/api/v1/google/connection/request", tokens.accessToken())).andReturn())
        .get("requestId").asText());
    LoginTokens admin = adminLogin();
    mockMvc.perform(authedPost(
        "/api/v1/admin/google/connection-requests/" + requestId + "/approve",
        admin.accessToken())).andReturn();

    MvcResult authorized = mockMvc.perform(
        authedGet("/api/v1/oauth/google/authorize", tokens.accessToken())).andReturn();
    assertEquals(200, authorized.getResponse().getStatus());
    assertTrue(json(authorized).get("authorizationUrl").asText().startsWith("https://"));
  }

  @Test
  void adminNotificationFailureDoesNotCorruptRequest() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("gcdmail"), "Str0ng-Enough-Password");
    mailCapture.failNextAdminNotice();
    try {
      MvcResult result = mockMvc.perform(authedPost(
          "/api/v1/google/connection/request", tokens.accessToken())).andReturn();
      assertEquals(200, result.getResponse().getStatus());
      assertEquals("PENDING", json(result).get("status").asText());
    } finally {
      mailCapture.disarmFailure();
    }
  }
}
