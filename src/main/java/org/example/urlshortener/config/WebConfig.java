package org.example.urlshortener.config;

import org.example.urlshortener.ratelimit.RateLimitInterceptor;
import org.example.urlshortener.ratelimit.RateLimiterRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimiterRegistry rateLimiterRegistry;

    public WebConfig(RateLimiterRegistry rateLimiterRegistry) {
        this.rateLimiterRegistry = rateLimiterRegistry;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(rateLimiterRegistry))
                .addPathPatterns("/api/v1/urls")
                .order(1);
    }
}
