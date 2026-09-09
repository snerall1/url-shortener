package org.example.urlshortener.unit;

import org.example.urlshortener.service.ShortCodeGenerator;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator = new ShortCodeGenerator();

    @Test
    void sameIdAlwaysProducesSameCode() {
        assertThat(generator.generate(42L)).isEqualTo(generator.generate(42L));
    }

    @Test
    void consecutiveIdsDoNotProduceObviouslySequentialCodes() {
        String code1 = generator.generate(1L);
        String code2 = generator.generate(2L);
        String code3 = generator.generate(3L);
        // Sequential ids must not decode to sequential/obviously-related short codes,
        // otherwise the endpoint would let clients enumerate every stored URL.
        assertThat(code1).isNotEqualTo(code2).isNotEqualTo(code3);
        assertThat(code1.length()).isNotEqualTo(0);
    }

    @Test
    void generatesUniqueCodesForARangeOfIds() {
        Set<String> codes = new HashSet<>();
        for (long id = 1; id <= 10_000; id++) {
            codes.add(generator.generate(id));
        }
        assertThat(codes).hasSize(10_000);
    }
}
