package com.ghostclean.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigInteger;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phase 12 trusted-edge client IP extraction (ADR-034).
 *
 * <p>{@code X-Forwarded-For} is honored ONLY when the direct connection
 * comes from a configured trusted proxy ({@code app.trusted-proxies}:
 * comma-separated IPs/CIDRs, empty = trust nothing). Otherwise the
 * direct remote address is used, so forged headers from untrusted
 * remotes can never poison rate-limit buckets or audit IP hashes.
 * Invalid trust entries are ignored; a blank forwarded header falls
 * back to the direct address.
 */
@Component
public class ClientIpResolver {

  private final List<Network> trusted;

  public ClientIpResolver(@Value("${app.trusted-proxies:}") String configured) {
    List<Network> parsed = new ArrayList<>();
    if (configured != null) {
      for (String entry : configured.split(",", -1)) {
        Network network = Network.parse(entry.trim());
        if (network != null) {
          parsed.add(network);
        }
      }
    }
    this.trusted = List.copyOf(parsed);
  }

  /** Resolves the client IP honoring forwarded headers from the edge only. */
  public String clientIp(HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded == null || forwarded.isBlank() || !isTrusted(remote)) {
      return remote;
    }
    String first = forwarded.split(",", -1)[0].trim();
    return first.isEmpty() ? remote : first;
  }

  private boolean isTrusted(String remote) {
    if (trusted.isEmpty()) {
      return false;
    }
    InetAddress address = toAddress(remote);
    if (address == null) {
      return false;
    }
    for (Network network : trusted) {
      if (network.contains(address)) {
        return true;
      }
    }
    return false;
  }

  private static InetAddress toAddress(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return InetAddress.getByName(raw.trim());
    } catch (Exception ex) {
      return null;
    }
  }

  /** Exact IP or CIDR network; unparsable entries yield null (ignored). */
  private record Network(BigInteger base, int bits, int totalBits) {

    static Network parse(String entry) {
      if (entry == null || entry.isEmpty()) {
        return null;
      }
      try {
        int slash = entry.indexOf('/');
        if (slash < 0) {
          InetAddress address = InetAddress.getByName(entry);
          return new Network(new BigInteger(1, address.getAddress()),
              address.getAddress().length * 8, address.getAddress().length * 8);
        }
        InetAddress address = InetAddress.getByName(entry.substring(0, slash));
        int total = address.getAddress().length * 8;
        int bits = Integer.parseInt(entry.substring(slash + 1).trim());
        if (bits < 0 || bits > total) {
          return null;
        }
        return new Network(new BigInteger(1, address.getAddress()), bits, total);
      } catch (Exception ex) {
        return null;
      }
    }

    boolean contains(InetAddress address) {
      byte[] raw = address.getAddress();
      if (raw.length * 8 != totalBits) {
        return false;
      }
      BigInteger candidate = new BigInteger(1, raw);
      if (bits == 0) {
        return true;
      }
      return candidate.shiftRight(totalBits - bits).equals(base.shiftRight(totalBits - bits));
    }
  }
}
