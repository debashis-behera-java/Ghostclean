package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ghostclean.auth.GmailVerificationEmailSender;
import com.ghostclean.auth.VerificationMailException;
import com.ghostclean.auth.VerificationMailProperties;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Gmail-SMTP sender tests. A fake {@link JavaMailSender} captures the MIME
 * message — no network, no credentials, no real delivery. Proves the SMTP
 * client shape (host/port/auth/STARTTLS), dynamic recipients, content
 * parity with the Resend path, fail-fast configuration, and leak-free
 * failure categories.
 */
class GmailVerificationEmailSenderTests {

  // -- client shape ----------------------------------------------------------

  @Test
  void defaultClientUsesGmailSmtpWithAuthAndStarttls() {
    VerificationMailProperties properties = baseProperties();
    JavaMailSender sender = GmailVerificationEmailSender.defaultClient(properties);

    assertTrue(sender instanceof JavaMailSenderImpl);
    JavaMailSenderImpl client = (JavaMailSenderImpl) sender;
    assertEquals("smtp.gmail.com", client.getHost());
    assertEquals(587, client.getPort());
    assertEquals("sender@gmail.com", client.getUsername());
    assertEquals("true", client.getJavaMailProperties().getProperty("mail.smtp.auth"));
    assertEquals("true", client.getJavaMailProperties().getProperty("mail.smtp.starttls.enable"));
  }

  @Test
  void missingSmtpCredentialsFailFast() {    VerificationMailProperties missingUser = baseProperties();
    missingUser.setSmtpUsername(" ");
    assertThrows(IllegalStateException.class,
        () -> new GmailVerificationEmailSender(missingUser).requireCompleteConfiguration());

    VerificationMailProperties missingPassword = baseProperties();
    missingPassword.setSmtpPassword("");
    assertThrows(IllegalStateException.class,
        () -> new GmailVerificationEmailSender(missingPassword).requireCompleteConfiguration());

    VerificationMailProperties missingFrom = baseProperties();
    missingFrom.setFrom(" ");
    assertThrows(IllegalStateException.class,
        () -> new GmailVerificationEmailSender(missingFrom).requireCompleteConfiguration());
  }

  @Test
  void alternateVariableNamesResolveWhenPrimaryIsBlank() {
    // Compose always defines every variable (empty when unset), so a blank
    // primary must fall through to the EMAIL_USERNAME/EMAIL_PASSWORD names.
    VerificationMailProperties properties = baseProperties();
    properties.setSmtpUsername("  ");
    properties.setSmtpPassword("");
    properties.setSmtpUsernameFallback("alt@gmail.com");
    properties.setSmtpPasswordFallback("alt-secret");
    assertEquals("alt@gmail.com", properties.effectiveSmtpUsername());
    assertEquals("alt-secret", properties.effectiveSmtpPassword());
    new GmailVerificationEmailSender(properties).requireCompleteConfiguration();

    JavaMailSender client =
        GmailVerificationEmailSender.defaultClient(properties);
    assertEquals("alt@gmail.com", ((JavaMailSenderImpl) client).getUsername());
  }

  @Test
  void primaryVariableNamesWinOverFallback() {
    VerificationMailProperties properties = baseProperties();
    properties.setSmtpUsernameFallback("alt@gmail.com");
    properties.setSmtpPasswordFallback("alt-secret");
    assertEquals("sender@gmail.com", properties.effectiveSmtpUsername());
    assertEquals("test-app-password", properties.effectiveSmtpPassword());
  }

  // -- payload -----------------------------------------------------------------

  @Test
  void recipientIsDynamicAndContentMatchesVerificationLink() throws Exception {
    FakeMailSender fake = new FakeMailSender();
    GmailVerificationEmailSender sender =
        new GmailVerificationEmailSender(baseProperties(), fake);

    sender.sendVerificationEmail("user1@gmail.com", "http://x/verify?token=tok-1");
    sender.sendVerificationEmail("user2@gmail.com", "http://x/verify?token=tok-2");

    assertEquals(2, fake.sent.size());
    assertEquals("user1@gmail.com", addressOf(fake.sent.get(0)));
    assertEquals("user2@gmail.com", addressOf(fake.sent.get(1)));
    String firstContent = textContentOf(fake.sent.get(0));
    assertTrue(firstContent.contains("tok-1"));
    assertTrue(subjectOf(fake.sent.get(0)).contains("GhostClean"));
    assertTrue(fromOf(fake.sent.get(0)).contains("sender@gmail.com"));
  }

  // -- failures ------------------------------------------------------------------

  @Test
  void smtpAuthFailureMapsToHttpAuthWithoutLeaks() {
    FakeMailSender fake = new FakeMailSender();
    fake.failWith(new MailAuthenticationException("bad credentials"));
    GmailVerificationEmailSender sender =
        new GmailVerificationEmailSender(baseProperties(), fake);

    VerificationMailException ex = assertThrows(VerificationMailException.class,
        () -> sender.sendVerificationEmail("user@gmail.com", "http://x?token=secret-tok"));
    assertEquals("http-auth", ex.getCategory());
    assertFalse(ex.getMessage().contains("secret-tok"));
    assertFalse(ex.getMessage().contains("user@gmail.com"));
  }

  @Test
  void genericSendFailureMapsToTransportWithoutLeaks() {
    FakeMailSender fake = new FakeMailSender();
    fake.failWith(new MailSendException("send failed"));
    GmailVerificationEmailSender sender =
        new GmailVerificationEmailSender(baseProperties(), fake);

    VerificationMailException ex = assertThrows(VerificationMailException.class,
        () -> sender.sendVerificationEmail("user@gmail.com", "http://x?token=secret-tok"));
    assertEquals("transport", ex.getCategory());
    assertFalse(ex.getMessage().contains("secret-tok"));
  }

  // -- helpers ---------------------------------------------------------------------

  private static VerificationMailProperties baseProperties() {
    VerificationMailProperties properties = new VerificationMailProperties();
    properties.setProvider("gmail");
    properties.setFrom("sender@gmail.com");
    properties.setFromName("GhostClean");
    properties.setSmtpUsername("sender@gmail.com");
    properties.setSmtpPassword("test-app-password");
    return properties;
  }

  private static String addressOf(MimeMessage message) throws MessagingException {
    Address[] recipients = message.getRecipients(Message.RecipientType.TO);
    return ((InternetAddress) recipients[0]).getAddress();
  }

  private static String subjectOf(MimeMessage message) throws MessagingException {
    return message.getSubject();
  }

  /** Concatenates all text/plain and text/html body parts of the message. */
  private static String textContentOf(MimeMessage message) throws Exception {
    StringBuilder out = new StringBuilder();
    collectText(message.getContent(), out);
    return out.toString();
  }

  private static void collectText(Object content, StringBuilder out) throws Exception {
    if (content instanceof String text) {
      out.append(text);
      return;
    }
    if (content instanceof jakarta.mail.Multipart multipart) {
      for (int i = 0; i < multipart.getCount(); i++) {
        collectText(multipart.getBodyPart(i).getContent(), out);
      }
    }
  }

  private static String fromOf(MimeMessage message) throws MessagingException {
    Address[] from = message.getFrom();
    return ((InternetAddress) from[0]).getAddress();
  }

  /** Capturing JavaMailSender double: no network, programmable failure. */
  static class FakeMailSender implements JavaMailSender {
    final List<MimeMessage> sent = new ArrayList<>();
    private RuntimeException failure;

    void failWith(RuntimeException failure) {
      this.failure = failure;
    }

    @Override
    public MimeMessage createMimeMessage() {
      return new MimeMessage(Session.getInstance(new Properties()));
    }

    @Override
    public MimeMessage createMimeMessage(java.io.InputStream contentStream) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void send(MimeMessage mimeMessage) {
      if (failure != null) {
        throw failure;
      }
      try {
        mimeMessage.saveChanges();
      } catch (MessagingException ex) {
        throw new IllegalStateException(ex);
      }
      sent.add(mimeMessage);
    }

    @Override
    public void send(MimeMessage... mimeMessages) {
      for (MimeMessage message : mimeMessages) {
        send(message);
      }
    }

    @Override
    public void send(org.springframework.mail.SimpleMailMessage simpleMessage) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void send(org.springframework.mail.SimpleMailMessage... simpleMessages) {
      throw new UnsupportedOperationException();
    }
  }
}
