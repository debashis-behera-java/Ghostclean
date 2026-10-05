package com.ghostclean;

import com.ghostclean.auth.VerificationEmailSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test mail capture: replaces the real sender so tests can extract the
 * issued verification token without any mail provider or log scraping.
 */
@TestConfiguration
public class TestMailCapture {

  @Bean
  @Primary
  public CapturingVerificationEmailSender capturingSender() {
    return new CapturingVerificationEmailSender();
  }

  public static class CapturingVerificationEmailSender implements VerificationEmailSender {

    private final List<SentMail> sent = new CopyOnWriteArrayList<>();
    private final List<SentMail> resetSent = new CopyOnWriteArrayList<>();
    private final List<AdminNotice> adminNotices = new CopyOnWriteArrayList<>();
    private volatile boolean failNextVerification = false;
    private volatile boolean failNextAdmin = false;

    @Override
    public void sendVerificationEmail(String toEmail, String verificationLink) {
      if (failNextVerification) {
        failNextVerification = false;
        throw new com.ghostclean.auth.VerificationMailException("http-auth");
      }
      sent.add(new SentMail(toEmail, verificationLink));
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String resetLink) {
      resetSent.add(new SentMail(toEmail, resetLink));
    }

    @Override
    public void sendAdminNotification(String toEmail, String subject, String textBody) {
      if (failNextAdmin) {
        failNextAdmin = false;
        throw new com.ghostclean.auth.VerificationMailException("transport");
      }
      adminNotices.add(new AdminNotice(toEmail, subject, textBody));
    }

    public List<SentMail> sent() {
      return List.copyOf(sent);
    }

    public int count() {
      return sent.size();
    }

    /** Next verification send throws an http-auth provider failure instead. */
    public void failNextVerification() {
      failNextVerification = true;
    }

    /** Disarms a pending forced failure (test cleanup; single-shot otherwise). */
    public void disarmFailure() {
      failNextVerification = false;
      failNextAdmin = false;
    }

    /** Next admin notice throws a transport failure instead. */
    public void failNextAdminNotice() {
      failNextAdmin = true;
    }

    public List<AdminNotice> adminNotices() {
      return List.copyOf(adminNotices);
    }

    /** Raw token from the most recent captured link. */
    public String latestToken() {
      String link = sent.get(sent.size() - 1).link();
      return link.substring(link.indexOf("token=") + "token=".length());
    }

    public int resetCount() {
      return resetSent.size();
    }

    /** Raw reset token from the most recent captured reset link. */
    public String latestResetToken() {
      String link = resetSent.get(resetSent.size() - 1).link();
      return link.substring(link.indexOf("token=") + "token=".length());
    }

    /** Most recent captured reset link (path/shape assertions only). */
    public String latestResetLink() {
      return resetSent.get(resetSent.size() - 1).link();
    }

    public record SentMail(String to, String link) {
    }

    public record AdminNotice(String to, String subject, String textBody) {
    }
  }
}
