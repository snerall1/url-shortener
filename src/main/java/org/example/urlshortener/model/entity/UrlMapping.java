package org.example.urlshortener.model.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "url_mapping", indexes = {
        @Index(name = "idx_short_code", columnList = "shortCode", unique = true)
})
public class UrlMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String shortCode;

    @Column(nullable = false, length = 2048)
    private String longUrl;

    @Column(nullable = false)
    private boolean customAlias;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant expiresAt;

    private Instant lastAccessedAt;

    @Column(nullable = false)
    private long clickCount;

    @Column(nullable = false)
    private boolean active = true;

    protected UrlMapping() {
        // JPA
    }

    public UrlMapping(String shortCode, String longUrl, boolean customAlias, Instant createdAt, Instant expiresAt) {
        this.shortCode = shortCode;
        this.longUrl = longUrl;
        this.customAlias = customAlias;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.clickCount = 0;
        this.active = true;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    public void recordClick(Instant when) {
        this.clickCount++;
        this.lastAccessedAt = when;
    }

    public Long getId() {
        return id;
    }

    public String getShortCode() {
        return shortCode;
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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }
}
