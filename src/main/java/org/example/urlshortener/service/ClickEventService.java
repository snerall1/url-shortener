package org.example.urlshortener.service;

import org.example.urlshortener.model.entity.ClickEvent;
import org.example.urlshortener.repository.ClickEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Records detailed click events off the redirect hot path. Recording is {@code @Async} and
 * intentionally swallows its own failures: a dropped analytics row must never turn into a
 * failed redirect for the end user. Click *counting* (see {@link UrlService#recordClick}) is
 * separate, synchronous, and atomic -- analytics detail is best-effort, the aggregate count is
 * not.
 */
@Service
public class ClickEventService {

    private static final Logger log = LoggerFactory.getLogger(ClickEventService.class);
    private static final int MAX_FIELD_LENGTH = 512;

    private final ClickEventRepository repository;

    public ClickEventService(ClickEventRepository repository) {
        this.repository = repository;
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAsync(String shortCode, String referrer, String userAgent, String clientIp) {
        try {
            ClickEvent event = new ClickEvent(
                    shortCode,
                    Instant.now(),
                    truncate(referrer),
                    truncate(userAgent),
                    hashIp(clientIp));
            repository.save(event);
        } catch (Exception e) {
            log.warn("Failed to record click analytics for shortCode={}: {}", shortCode, e.getMessage());
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > MAX_FIELD_LENGTH ? value.substring(0, MAX_FIELD_LENGTH) : value;
    }

    private String hashIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(clientIp.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
