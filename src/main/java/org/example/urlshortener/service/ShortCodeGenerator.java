package org.example.urlshortener.service;

import org.example.urlshortener.util.Base62Encoder;
import org.springframework.stereotype.Component;

/**
 * Turns a persisted row's identity id into a short code.
 * <p>
 * Design decision: rather than generating a random code and retrying on collision, we encode the
 * database identity (auto-increment PK) directly. This guarantees uniqueness with zero collision
 * retries and zero extra round-trips. The raw identity is XORed with a fixed 64-bit mask before
 * Base62 encoding so that consecutive inserts do not produce visibly sequential codes (id=1,2,3
 * would otherwise decode trivially and let a client enumerate/scrape every short URL in the
 * system). The XOR is bijective, so decoding is not required for correctness -- lookups are by
 * the stored shortCode string, never by reversing the mask.
 * <p>
 * Trade-off (documented in docs/RISKS.md): this is still a deterministic, invertible transform,
 * not cryptographic obfuscation. A determined attacker who learns the mask could still predict
 * codes. For this prototype's threat model (opportunistic scraping, not a security boundary)
 * that is an accepted risk; a production system exposed to hostile enumeration should mix in a
 * per-tenant secret or switch to random-with-retry generation.
 */
@Component
public class ShortCodeGenerator {

    private static final long MASK = 0x5DEECE66DL;

    public String generate(long id) {
        return Base62Encoder.encode(id ^ MASK);
    }
}
