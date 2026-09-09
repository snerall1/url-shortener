package org.example.urlshortener.repository;

import org.example.urlshortener.model.entity.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    List<ClickEvent> findByShortCodeAndOccurredAtGreaterThanEqual(String shortCode, Instant since);

    @Query("select coalesce(c.referrer, 'direct'), count(c) from ClickEvent c " +
            "where c.shortCode = :shortCode group by coalesce(c.referrer, 'direct') order by count(c) desc")
    List<Object[]> topReferrers(@Param("shortCode") String shortCode);

    @Query("select coalesce(c.userAgent, 'unknown'), count(c) from ClickEvent c " +
            "where c.shortCode = :shortCode group by coalesce(c.userAgent, 'unknown') order by count(c) desc")
    List<Object[]> topUserAgents(@Param("shortCode") String shortCode);
}
