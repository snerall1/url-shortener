package org.example.urlshortener.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.urlshortener.exception.RateLimitExceededException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies a per-client token bucket to the URL-creation endpoint only. Redirects are
 * intentionally exempt (rate limiting the hot 302 path would defeat the purpose of the service
 * for legitimate high-traffic short links); creation is the endpoint worth protecting from
 * abuse/spam.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiterRegistry registry;

    public RateLimitInterceptor(RateLimiterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String clientKey = resolveClientKey(request);
        TokenBucket bucket = registry.bucketFor(clientKey);
        if (!bucket.tryConsume()) {
            throw new RateLimitExceededException(bucket.secondsUntilNextToken());
        }
        return true;
    }

    private String resolveClientKey(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
