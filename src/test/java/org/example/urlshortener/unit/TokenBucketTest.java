package org.example.urlshortener.unit;

import org.example.urlshortener.ratelimit.TokenBucket;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {

    @Test
    void allowsRequestsUpToCapacity() {
        TokenBucket bucket = new TokenBucket(3, 1);
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isFalse();
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(1, 20); // refills a token every 50ms
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isFalse();

        Thread.sleep(150);

        assertThat(bucket.tryConsume()).isTrue();
    }

    @Test
    void reportsSecondsUntilNextTokenWhenExhausted() {
        TokenBucket bucket = new TokenBucket(1, 1); // 1 token/sec
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.secondsUntilNextToken()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void doesNotExceedCapacityAfterLongIdlePeriod() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(2, 100);
        Thread.sleep(100);
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isFalse();
    }
}
