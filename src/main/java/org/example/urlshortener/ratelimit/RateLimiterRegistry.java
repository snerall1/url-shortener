package org.example.urlshortener.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds one {@link TokenBucket} per client key (IP address). Buckets are created lazily and
 * evicted by {@link org.example.urlshortener.scheduler.CleanupScheduler} after a period of
 * inactivity to bound memory use, since this is an unauthenticated, IP-keyed map.
 */
@Component
public class RateLimiterRegistry {

    private final Map<String, BucketEntry> buckets = new ConcurrentHashMap<>();
    private final double capacity;
    private final double refillPerSecond;

    public RateLimiterRegistry(
            @Value("${app.rate-limit.capacity:10}") double capacity,
            @Value("${app.rate-limit.refill-per-second:1}") double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    public TokenBucket bucketFor(String clientKey) {
        BucketEntry entry = buckets.computeIfAbsent(clientKey,
                k -> new BucketEntry(new TokenBucket(capacity, refillPerSecond)));
        entry.lastUsed = Instant.now();
        return entry.bucket;
    }

    public void evictInactiveSince(Instant cutoff) {
        buckets.entrySet().removeIf(e -> e.getValue().lastUsed.isBefore(cutoff));
    }

    public int size() {
        return buckets.size();
    }

    private static final class BucketEntry {
        final TokenBucket bucket;
        volatile Instant lastUsed = Instant.now();

        BucketEntry(TokenBucket bucket) {
            this.bucket = bucket;
        }
    }
}
