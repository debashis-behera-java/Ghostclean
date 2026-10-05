package com.ghostclean.services;

import com.ghostclean.detection.DomainNormalizer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;

/**
 * Directory lookup: exact domain → subdomain → none. The active catalog is
 * cached in memory and refreshed from the database (small by design — a
 * handful of curated rows, never thousands). Unknown senders resolve empty
 * and fall back to registrable-domain grouping downstream.
 */
@Service
public class ServiceDirectoryService {

  private final ServiceDirectoryRepository repository;
  private volatile List<ServiceDirectory> cache = List.of();

  public ServiceDirectoryService(ServiceDirectoryRepository repository) {
    this.repository = repository;
    refresh();
  }

  /** Reloads the active catalog (used at startup and by future admin flows). */
  public synchronized void refresh() {
    List<ServiceDirectory> active = new CopyOnWriteArrayList<>(
        repository.findByStatus("active"));
    this.cache = List.copyOf(active);
  }

  public Optional<ServiceDirectory> lookup(String senderDomain) {
    String normalized = DomainNormalizer.asciiLower(senderDomain);
    if (normalized.isEmpty()) {
      return Optional.empty();
    }
    for (ServiceDirectory entry : cache) {
      if (entry.matchesDomain(normalized)) {
        return Optional.of(entry);
      }
    }
    for (ServiceDirectory entry : cache) {
      if (entry.matchesSubdomain(normalized)) {
        return Optional.of(entry);
      }
    }
    return Optional.empty();
  }

  public int catalogSize() {
    return cache.size();
  }
}
