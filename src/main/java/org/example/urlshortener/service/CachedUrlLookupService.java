package org.example.urlshortener.service;

import org.example.urlshortener.config.CacheConfig;
import org.example.urlshortener.model.entity.UrlMapping;
import org.example.urlshortener.repository.UrlMappingRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caches only the raw "does an active mapping exist for this code" DB lookup. Deliberately
 * does NOT perform the expiry check itself: {@code @Cacheable} skips re-executing the method
 * body on a cache hit, so if expiry were checked here a mapping cached while still valid would
 * keep being served as valid for the lifetime of the cache entry, well past its real
 * {@code expiresAt}. The caller ({@link UrlService#resolveForRedirect}) re-evaluates expiry on
 * every call, cached or not -- only immutable data (longUrl, expiresAt, active-at-cache-time)
 * is ever cached here, and active-state changes (delete) explicitly evict this cache.
 */
@Service
public class CachedUrlLookupService {

    private final UrlMappingRepository repository;

    public CachedUrlLookupService(UrlMappingRepository repository) {
        this.repository = repository;
    }

    @Cacheable(value = CacheConfig.URL_LOOKUP_CACHE, key = "#shortCode", unless = "#result == null")
    @Transactional(readOnly = true)
    public UrlMapping findActiveByShortCode(String shortCode) {
        return repository.findByShortCodeAndActiveTrue(shortCode).orElse(null);
    }
}
