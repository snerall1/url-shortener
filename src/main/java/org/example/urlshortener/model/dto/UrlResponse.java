package org.example.urlshortener.model.dto;

import org.example.urlshortener.model.entity.UrlMapping;

import java.time.Instant;

public class UrlResponse {

    private final String shortCode;
    private final String shortUrl;
    private final String longUrl;
    private final boolean customAlias;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Instant lastAccessedAt;
    private final long clickCount;

    public UrlResponse(String shortCode, String shortUrl, String longUrl, boolean customAlias,
                        Instant createdAt, Instant expiresAt, Instant lastAccessedAt, long clickCount) {
        this.shortCode = shortCode;
        this.shortUrl = shortUrl;
        this.longUrl = longUrl;
        this.customAlias = customAlias;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.lastAccessedAt = lastAccessedAt;
        this.clickCount = clickCount;
    }

    public static UrlResponse from(UrlMapping mapping, String baseUrl) {
        return new UrlResponse(
                mapping.getShortCode(),
                baseUrl + "/" + mapping.getShortCode(),
                mapping.getLongUrl(),
                mapping.isCustomAlias(),
                mapping.getCreatedAt(),
                mapping.getExpiresAt(),
                mapping.getLastAccessedAt(),
                mapping.getClickCount()
        );
    }

    public String getShortCode() {
        return shortCode;
    }

    public String getShortUrl() {
        return shortUrl;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public boolean isCustomAlias() {
        return customAlias;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public long getClickCount() {
        return clickCount;
    }
}
