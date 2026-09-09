package org.example.urlshortener.scheduler;

import org.example.urlshortener.model.entity.UrlMapping;
import org.example.urlshortener.ratelimit.RateLimiterRegistry;
import org.example.urlshortener.repository.UrlMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Two lightweight housekeeping jobs, both bounded and idempotent so they are safe to run
 * repeatedly and safe to skip a cycle:
 * <ul>
 *   <li>Soft-deactivate expired short URLs so they stop resolving via the redirect endpoint
 *       (rows are kept, not hard-deleted, so analytics history survives expiry).</li>
 *   <li>Evict rate-limit buckets that have been idle, bounding the in-memory map's size since
 *       it is keyed by client IP with no natural cap.</li>
 * </ul>
 */
@Component
public class CleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(CleanupScheduler.class);

    private final UrlMappingRepository urlMappingRepository;
    private final RateLimiterRegistry rateLimiterRegistry;

    public CleanupScheduler(UrlMappingRepository urlMappingRepository, RateLimiterRegistry rateLimiterRegistry) {
        this.urlMappingRepository = urlMappingRepository;
        this.rateLimiterRegistry = rateLimiterRegistry;
    }

    @Scheduled(fixedDelayString = "${app.cleanup.expired-url-interval-ms:300000}")
    @Transactional
    public void deactivateExpiredUrls() {
        List<UrlMapping> expired = urlMappingRepository.findByActiveTrueAndExpiresAtBefore(Instant.now());
        if (expired.isEmpty()) {
            return;
        }
        expired.forEach(mapping -> mapping.setActive(false));
        urlMappingRepository.saveAll(expired);
        log.info("Deactivated {} expired short URL(s)", expired.size());
    }

    @Scheduled(fixedDelayString = "${app.cleanup.rate-limit-interval-ms:600000}")
    public void evictIdleRateLimitBuckets() {
        Instant cutoff = Instant.now().minusSeconds(3600);
        rateLimiterRegistry.evictInactiveSince(cutoff);
    }
}
