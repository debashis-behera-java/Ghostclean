package com.ghostclean.services;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceDirectoryRepository extends JpaRepository<ServiceDirectory, UUID> {

  List<ServiceDirectory> findByStatus(String status);

  Optional<ServiceDirectory> findFirstBySlugIgnoreCase(String slug);
}
