package org.example.urlshortener.ratelimit;

/**
 * Thread-safe token bucket. Tokens refill continuously at {@code refillTokensPerSecond} up to
 * {@code capacity}; each request consumes one token. Lock-free via compare-and-swap retry loop
 * on a packed (tokens, lastRefillNanos) pair is avoided for simplicity -- a single synchronized
 * method is sufficient at the throughput this prototype targets and keeps the logic easy to
 * verify. See docs/RISKS.md for the scaling trade-off (in-process, not shared across nodes).
 */
public class TokenBucket {

    private final double capacity;
    private final double refillTokensPerSecond;
    private double availableTokens;
    private long lastRefillNanos;

    public TokenBucket(double capacity, double refillTokensPerSecond) {
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        this.availableTokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    public synchronized boolean tryConsume() {
        refill();
        if (availableTokens >= 1.0) {
            availableTokens -= 1.0;
            return true;
        }
        return false;
    }

    public synchronized long secondsUntilNextToken() {
        refill();
        if (availableTokens >= 1.0) {
            return 0;
        }
        double deficit = 1.0 - availableTokens;
        double seconds = deficit / refillTokensPerSecond;
        return Math.max(1, Math.round(Math.ceil(seconds)));
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds <= 0) {
            return;
        }
        availableTokens = Math.min(capacity, availableTokens + elapsedSeconds * refillTokensPerSecond);
        lastRefillNanos = now;
    }
}
