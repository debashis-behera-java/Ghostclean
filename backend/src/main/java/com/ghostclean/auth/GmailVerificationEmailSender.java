package com.ghostclean.auth;

import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Gmail-SMTP account-mail sender.
 *
 * <p>Active only when {@code verification.mail.provider=gmail}; the Resend
 * implementation and this one are mutually exclusive by provider id, and the
 * Phase 2 silent/log sender remains the fallback otherwise. Sends GhostClean
 * account mail (verification and password-reset links) through Gmail SMTP
 * (smtp.gmail.com:587, STARTTLS, authenticated) using the Gmail address and
 * App Password from the environment only.
 *
 * <p>Same contract as the Resend sender: single attempt with strict
 * timeouts (connect 3s, read per {@code timeout-ms}); any failure throws
 * {@link VerificationMailException} carrying only a coarse category so
 * registration stays 201-shaped and resend stays 202-shaped.
 *
 * <p>Logging discipline: outcome category only. Never the SMTP password,
 * the recipient address, the account link, or message content.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "verification.mail", name = "provider", havingValue = "gmail")
public class GmailVerificationEmailSender implements VerificationEmailSender {

  private static final Logger log = LoggerFactory.getLogger(GmailVerificationEmailSender.class);

  private final VerificationMailProperties properties;
  private final JavaMailSender mail;

  @Autowired
  public GmailVerificationEmailSender(VerificationMailProperties properties) {
    this(properties, defaultClient(properties));
  }

  /** Test seam: inject a fake mail client. Public for cross-package tests. */
  public GmailVerificationEmailSender(VerificationMailProperties properties, JavaMailSender mail) {
    this.properties = properties;
    this.mail = mail;
  }
  /** Fail fast on incomplete provider configuration (never at first signup). */
  @PostConstruct
  public void requireCompleteConfiguration() {
    if (properties.getFrom() == null || properties.getFrom().isBlank()) {
      throw new IllegalStateException(
          "verification.mail.from is required when verification.mail.provider=gmail");
    }
    if (properties.effectiveSmtpUsername().isBlank()) {
      throw new IllegalStateException(
          "verification.mail.smtp-username (or EMAIL_USERNAME) is required"
              + " when verification.mail.provider=gmail");
    }
    if (properties.effectiveSmtpPassword().isBlank()) {
      throw new IllegalStateException(
          "verification.mail.smtp-password (or EMAIL_PASSWORD) is required"
              + " when verification.mail.provider=gmail");
    }
    log.info("Verification mail provider=gmail configured host={} port={}",
        properties.getSmtpHost(), properties.getSmtpPort());
  }

  /** Builds the STARTTLS Gmail SMTP client. Public for cross-package tests. */
  public static JavaMailSender defaultClient(VerificationMailProperties properties) {
    JavaMailSenderImpl client = new JavaMailSenderImpl();
    client.setHost(properties.getSmtpHost());
    client.setPort(properties.getSmtpPort());
    client.setUsername(properties.effectiveSmtpUsername());
    client.setPassword(properties.effectiveSmtpPassword());
    Properties props = client.getJavaMailProperties();
    props.put("mail.transport.protocol", "smtp");
    props.put("mail.smtp.auth", "true");
    props.put("mail.smtp.starttls.enable", "true");
    props.put("mail.smtp.connectiontimeout", "3000");
    props.put("mail.smtp.timeout", String.valueOf(Math.max(1, properties.getTimeoutMs())));
    return client;
  }

  @Override
  public void sendVerificationEmail(String toEmail, String verificationLink) {
    VerificationEmailContent.EmailBody body = VerificationEmailContent.build(verificationLink);
    postAccountEmail(toEmail, body.subject(), body.html(), body.text(), "verification");
  }

  @Override
  public void sendPasswordResetEmail(String toEmail, String resetLink) {
    PasswordResetEmailContent.EmailBody body = PasswordResetEmailContent.build(resetLink);
    postAccountEmail(toEmail, body.subject(), body.html(), body.text(), "password-reset");
  }

  @Override
  public void sendAdminNotification(String toEmail, String subject, String textBody) {
    String safeHtml = "<pre>" + VerificationEmailContent.escapeHtml(textBody) + "</pre>";
    postAccountEmail(toEmail, subject, safeHtml, textBody, "admin-notice");
  }

  private void postAccountEmail(
      String toEmail, String subject, String html, String text, String kind) {
    long started = System.currentTimeMillis();
    try {
      MimeMessage message = mail.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
      helper.setFrom(sender());
      helper.setTo(toEmail);
      helper.setSubject(subject);
      helper.setText(text, html);
      mail.send(message);
      log.info("Account email delivery succeeded kind={} latencyMs={}",
          kind, System.currentTimeMillis() - started);
    } catch (MailAuthenticationException ex) {
      log.warn("Account email delivery failed kind={} reason=http-auth latencyMs={}",
          kind, System.currentTimeMillis() - started);
      throw new VerificationMailException("http-auth");
    } catch (MailException ex) {
      String category = classify(ex);
      log.warn("Account email delivery failed kind={} reason={} latencyMs={}",
          kind, category, System.currentTimeMillis() - started);
      throw new VerificationMailException(category);
    } catch (MessagingException ex) {
      log.warn("Account email delivery failed kind={} reason=error latencyMs={}",
          kind, System.currentTimeMillis() - started);
      throw new VerificationMailException("transport");
    }
  }

  private String sender() {
    String name = properties.getFromName() == null ? "" : properties.getFromName().trim();
    if (name.isEmpty()) {
      return properties.getFrom();
    }
    return name + " <" + properties.getFrom() + ">";
  }

  /** Outcome category for safe logging — never leaks response content. */
  private static String classify(MailException ex) {
    String name = ex.getClass().getSimpleName().toLowerCase();
    String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
    if (name.contains("timeout") || message.contains("timed out")) {
      return "timeout";
    }
    return "transport";
  }
}
