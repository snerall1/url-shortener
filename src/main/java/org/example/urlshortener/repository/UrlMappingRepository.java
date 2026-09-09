package org.example.urlshortener.repository;

import org.example.urlshortener.model.entity.UrlMapping;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    Optional<UrlMapping> findByShortCodeAndActiveTrue(String shortCode);

    boolean existsByShortCode(String shortCode);

    Page<UrlMapping> findByActiveTrue(Pageable pageable);

    List<UrlMapping> findByActiveTrueAndExpiresAtBefore(Instant cutoff);

    /**
     * Atomic counter increment at the DB level. Deliberately avoids the read-mutate-save
     * pattern on the (potentially cache-shared) entity object, which would race under
     * concurrent redirects and lose updates.
     */
    @Modifying
    @Query("update UrlMapping u set u.clickCount = u.clickCount + 1, u.lastAccessedAt = :when " +
            "where u.shortCode = :shortCode")
    int incrementClickCount(@Param("shortCode") String shortCode, @Param("when") Instant when);
}
