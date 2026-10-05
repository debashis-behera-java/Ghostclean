package com.ghostclean.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Authentication knobs (Phase 2). Every value is overridable from the
 * environment; secrets have no default and fail fast when missing.
 */
@Component
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

  /** HS256 signing secret (min 32 chars). From {@code JWT_SIGNING_KEY}. */
  private String jwtSecret = "";

  private String issuer = "ghostclean";
  private Duration accessTokenTtl = Duration.ofMinutes(15);
  private Duration refreshTokenTtl = Duration.ofDays(30);
  private Duration verificationTokenTtl = Duration.ofHours(24);
  private Duration resetTokenTtl = Duration.ofMinutes(30);
  private int passwordMinLength = 12;
  private int passwordMaxLength = 128;
  private String cookieName = "gc_refresh";
  /** Secure cookies by default; local http dev may set false via env. */
  private boolean cookieSecure = true;
  /** Verification-mail mode: {@code silent} (default) or {@code log} (local dev only). */
  private String mailMode = "silent";

  private final RateLimits rateLimits = new RateLimits();

  public String getJwtSecret() {
    return jwtSecret;
  }

  public void setJwtSecret(String jwtSecret) {
    this.jwtSecret = jwtSecret;
  }

  public String getIssuer() {
    return issuer;
  }

  public void setIssuer(String issuer) {
    this.issuer = issuer;
  }

  public Duration getAccessTokenTtl() {
    return accessTokenTtl;
  }

  public void setAccessTokenTtl(Duration accessTokenTtl) {
    this.accessTokenTtl = accessTokenTtl;
  }

  public Duration getRefreshTokenTtl() {
    return refreshTokenTtl;
  }

  public void setRefreshTokenTtl(Duration refreshTokenTtl) {
    this.refreshTokenTtl = refreshTokenTtl;
  }

  public Duration getVerificationTokenTtl() {
    return verificationTokenTtl;
  }

  public void setVerificationTokenTtl(Duration verificationTokenTtl) {
    this.verificationTokenTtl = verificationTokenTtl;
  }

  public Duration getResetTokenTtl() {
    return resetTokenTtl;
  }

  public void setResetTokenTtl(Duration resetTokenTtl) {
    this.resetTokenTtl = resetTokenTtl;
  }

  public int getPasswordMinLength() {
    return passwordMinLength;
  }

  public void setPasswordMinLength(int passwordMinLength) {
    this.passwordMinLength = passwordMinLength;
  }

  public int getPasswordMaxLength() {
    return passwordMaxLength;
  }

  public void setPasswordMaxLength(int passwordMaxLength) {
    this.passwordMaxLength = passwordMaxLength;
  }

  public String getCookieName() {
    return cookieName;
  }

  public void setCookieName(String cookieName) {
    this.cookieName = cookieName;
  }

  public boolean isCookieSecure() {
    return cookieSecure;
  }

  public void setCookieSecure(boolean cookieSecure) {
    this.cookieSecure = cookieSecure;
  }

  public String getMailMode() {
    return mailMode;
  }

  public void setMailMode(String mailMode) {
    this.mailMode = mailMode;
  }

  public RateLimits getRateLimits() {
    return rateLimits;
  }

  /** Abuse-protection budgets. In-memory enforcement (single instance). */
  public static class RateLimits {

    private int loginPerMinutePerIp = 5;
    private int registerPerMinutePerIp = 5;
    private int verifyPerMinutePerIp = 10;
    private int resendPerMinutePerIp = 10;
    private int resendPerDayPerEmail = 5;
    private int refreshPerMinutePerIp = 30;
    private int forgotPerMinutePerIp = 10;
    private int forgotPerDayPerEmail = 5;
    private int resetPerMinutePerIp = 10;
    private int maxFailedLogins = 5;
    private Duration lockout = Duration.ofMinutes(15);

    public int getLoginPerMinutePerIp() {
      return loginPerMinutePerIp;
    }

    public void setLoginPerMinutePerIp(int loginPerMinutePerIp) {
      this.loginPerMinutePerIp = loginPerMinutePerIp;
    }

    public int getRegisterPerMinutePerIp() {
      return registerPerMinutePerIp;
    }

    public void setRegisterPerMinutePerIp(int registerPerMinutePerIp) {
      this.registerPerMinutePerIp = registerPerMinutePerIp;
    }

    public int getVerifyPerMinutePerIp() {
      return verifyPerMinutePerIp;
    }

    public void setVerifyPerMinutePerIp(int verifyPerMinutePerIp) {
      this.verifyPerMinutePerIp = verifyPerMinutePerIp;
    }

    public int getResendPerMinutePerIp() {
      return resendPerMinutePerIp;
    }

    public void setResendPerMinutePerIp(int resendPerMinutePerIp) {
      this.resendPerMinutePerIp = resendPerMinutePerIp;
    }

    public int getResendPerDayPerEmail() {
      return resendPerDayPerEmail;
    }

    public void setResendPerDayPerEmail(int resendPerDayPerEmail) {
      this.resendPerDayPerEmail = resendPerDayPerEmail;
    }

    public int getRefreshPerMinutePerIp() {
      return refreshPerMinutePerIp;
    }

    public void setRefreshPerMinutePerIp(int refreshPerMinutePerIp) {
      this.refreshPerMinutePerIp = refreshPerMinutePerIp;
    }

    public int getForgotPerMinutePerIp() {
      return forgotPerMinutePerIp;
    }

    public void setForgotPerMinutePerIp(int forgotPerMinutePerIp) {
      this.forgotPerMinutePerIp = forgotPerMinutePerIp;
    }

    public int getForgotPerDayPerEmail() {
      return forgotPerDayPerEmail;
    }

    public void setForgotPerDayPerEmail(int forgotPerDayPerEmail) {
      this.forgotPerDayPerEmail = forgotPerDayPerEmail;
    }

    public int getResetPerMinutePerIp() {
      return resetPerMinutePerIp;
    }

    public void setResetPerMinutePerIp(int resetPerMinutePerIp) {
      this.resetPerMinutePerIp = resetPerMinutePerIp;
    }

    public int getMaxFailedLogins() {
      return maxFailedLogins;
    }

    public void setMaxFailedLogins(int maxFailedLogins) {
      this.maxFailedLogins = maxFailedLogins;
    }

    public Duration getLockout() {
      return lockout;
    }

    public void setLockout(Duration lockout) {
      this.lockout = lockout;
    }
  }
}
