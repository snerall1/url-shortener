package org.example.urlshortener.model.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "click_event", indexes = {
        @Index(name = "idx_click_short_code", columnList = "shortCode"),
        @Index(name = "idx_click_occurred_at", columnList = "occurredAt")
})
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String shortCode;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(length = 512)
    private String referrer;

    @Column(length = 512)
    private String userAgent;

    /**
     * SHA-256 hash of the client IP, never the raw IP, to avoid storing PII while still
     * enabling rough unique-visitor estimation.
     */
    @Column(length = 64)
    private String ipHash;

    protected ClickEvent() {
        // JPA
    }

    public ClickEvent(String shortCode, Instant occurredAt, String referrer, String userAgent, String ipHash) {
        this.shortCode = shortCode;
        this.occurredAt = occurredAt;
        this.referrer = referrer;
        this.userAgent = userAgent;
        this.ipHash = ipHash;
    }

    public Long getId() {
        return id;
    }

    public String getShortCode() {
        return shortCode;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getReferrer() {
        return referrer;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getIpHash() {
        return ipHash;
    }
}
