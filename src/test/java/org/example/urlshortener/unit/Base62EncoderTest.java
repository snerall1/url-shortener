package org.example.urlshortener.unit;

import org.example.urlshortener.util.Base62Encoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base62EncoderTest {

    @Test
    void encodesZeroAsFirstAlphabetChar() {
        assertThat(Base62Encoder.encode(0)).isEqualTo("0");
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 61, 62, 63, 12345, 999_999_999L, Long.MAX_VALUE})
    void encodeThenDecodeRoundTrips(long value) {
        String encoded = Base62Encoder.encode(value);
        assertThat(Base62Encoder.decode(encoded)).isEqualTo(value);
    }

    @Test
    void distinctInputsProduceDistinctCodes() {
        assertThat(Base62Encoder.encode(1)).isNotEqualTo(Base62Encoder.encode(2));
        assertThat(Base62Encoder.encode(61)).isNotEqualTo(Base62Encoder.encode(62));
    }

    @Test
    void rejectsNegativeValues() {
        assertThatThrownBy(() -> Base62Encoder.encode(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidCharactersOnDecode() {
        assertThatThrownBy(() -> Base62Encoder.decode("abc!")).isInstanceOf(IllegalArgumentException.class);
    }
}
