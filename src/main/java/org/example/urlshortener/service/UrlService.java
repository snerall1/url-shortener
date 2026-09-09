package org.example.urlshortener.service;

import org.example.urlshortener.config.CacheConfig;
import org.example.urlshortener.exception.AliasConflictException;
import org.example.urlshortener.exception.InvalidUrlException;
import org.example.urlshortener.exception.UrlExpiredException;
import org.example.urlshortener.exception.UrlNotFoundException;
import org.example.urlshortener.model.dto.CreateUrlRequest;
import org.example.urlshortener.model.dto.UrlResponse;
import org.example.urlshortener.model.entity.UrlMapping;
import org.example.urlshortener.repository.UrlMappingRepository;
import org.example.urlshortener.util.UrlValidator;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class UrlService {

    private final UrlMappingRepository repository;
    private final ShortCodeGenerator shortCodeGenerator;
    private final CachedUrlLookupService cachedUrlLookupService;

    public UrlService(UrlMappingRepository repository, ShortCodeGenerator shortCodeGenerator,
                       CachedUrlLookupService cachedUrlLookupService) {
        this.repository = repository;
        this.shortCodeGenerator = shortCodeGenerator;
        this.cachedUrlLookupService = cachedUrlLookupService;
    }

    @Transactional
    public UrlResponse createShortUrl(CreateUrlRequest request, String baseUrl) {
        UrlValidator.validate(request.getLongUrl());

        Instant now = Instant.now();
        Instant expiresAt = request.getTtlSeconds() != null && request.getTtlSeconds() > 0
                ? now.plusSeconds(request.getTtlSeconds())
                : null;

        String alias = request.getCustomAlias();
        UrlMapping saved;
        if (alias != null && !alias.isBlank()) {
            saved = createWithCustomAlias(alias, request.getLongUrl(), now, expiresAt);
        } else {
            saved = createWithGeneratedCode(request.getLongUrl(), now, expiresAt);
        }
        return UrlResponse.from(saved, baseUrl);
    }

    private UrlMapping createWithCustomAlias(String alias, String longUrl, Instant now, Instant expiresAt) {
        if (UrlValidator.isReservedPath(alias)) {
            throw new InvalidUrlException("customAlias '" + alias + "' is reserved and cannot be used");
        }
        if (repository.existsByShortCode(alias)) {
            throw new AliasConflictException(alias);
        }
        return repository.save(new UrlMapping(alias, longUrl, true, now, expiresAt));
    }

    private UrlMapping createWithGeneratedCode(String longUrl, Instant now, Instant expiresAt) {
        // Two-phase insert: persist with a unique placeholder to satisfy the not-null/unique
        // constraint, flush to obtain the generated identity, then derive the real short code
        // from that identity and update. See ShortCodeGenerator for why we encode the id
        // rather than generate-and-retry a random code.
        String placeholder = "~pending~" + UUID.randomUUID();
        UrlMapping mapping = new UrlMapping(placeholder, longUrl, false, now, expiresAt);
        UrlMapping withId = repository.saveAndFlush(mapping);

        String code = shortCodeGenerator.generate(withId.getId());
        withId.setShortCode(code);
        return repository.save(withId);
    }

    @Transactional(readOnly = true)
    public UrlMapping resolveForRedirect(String shortCode) {
        UrlMapping mapping = cachedUrlLookupService.findActiveByShortCode(shortCode);
        if (mapping == null) {
            throw new UrlNotFoundException(shortCode);
        }
        // Always re-checked, even on a cache hit -- see CachedUrlLookupService for why expiry
        // must not be evaluated inside the cached method itself.
        if (mapping.isExpired(Instant.now())) {
            throw new UrlExpiredException(shortCode);
        }
        return mapping;
    }

    @Transactional(readOnly = true)
    public UrlResponse getMetadata(String shortCode, String baseUrl) {
        UrlMapping mapping = repository.findByShortCodeAndActiveTrue(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
        return UrlResponse.from(mapping, baseUrl);
    }

    @Transactional(readOnly = true)
    public Page<UrlResponse> listUrls(Pageable pageable, String baseUrl) {
        return repository.findByActiveTrue(pageable).map(mapping -> UrlResponse.from(mapping, baseUrl));
    }

    @Transactional
    @CacheEvict(value = CacheConfig.URL_LOOKUP_CACHE, key = "#shortCode")
    public void deleteUrl(String shortCode) {
        UrlMapping mapping = repository.findByShortCodeAndActiveTrue(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
        mapping.setActive(false);
        repository.save(mapping);
    }

    /**
     * Atomically increments the click counter for the given short code. Called from the
     * redirect hot path immediately after the (cached) lookup resolves -- deliberately does
     * NOT mutate the resolved {@link UrlMapping} instance directly, since that object may be a
     * shared cache entry read concurrently by other in-flight redirects; a non-atomic
     * read-increment-write on a shared object would lose updates under concurrency.
     */
    @Transactional
    public void recordClick(String shortCode) {
        repository.incrementClickCount(shortCode, Instant.now());
    }
}
