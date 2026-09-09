package org.example.urlshortener.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public class CreateUrlRequest {

    @NotBlank(message = "longUrl is required")
    @Size(max = 2048, message = "longUrl must not exceed 2048 characters")
    private String longUrl;

    @Size(min = 4, max = 32, message = "customAlias must be between 4 and 32 characters")
    @Pattern(regexp = "^[a-zA-Z0-9_-]*$", message = "customAlias may only contain letters, digits, '-' and '_'")
    private String customAlias;

    @PositiveOrZero(message = "ttlSeconds must be zero or positive")
    private Long ttlSeconds;

    public CreateUrlRequest() {
    }

    public CreateUrlRequest(String longUrl, String customAlias, Long ttlSeconds) {
        this.longUrl = longUrl;
        this.customAlias = customAlias;
        this.ttlSeconds = ttlSeconds;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public void setLongUrl(String longUrl) {
        this.longUrl = longUrl;
    }

    public String getCustomAlias() {
        return customAlias;
    }

    public void setCustomAlias(String customAlias) {
        this.customAlias = customAlias;
    }

    public Long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(Long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }
}
