package org.example.urlshortener.unit;

import org.example.urlshortener.exception.InvalidUrlException;
import org.example.urlshortener.util.UrlValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "http://example.com/path?x=1", "https://sub.example.com:8443/a/b"})
    void acceptsWellFormedHttpAndHttpsUrls(String url) {
        UrlValidator.validate(url); // should not throw
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "file:///etc/passwd",
            "data:text/html,<script>alert(1)</script>",
            "ftp://example.com/file"
    })
    void rejectsDisallowedSchemes(String url) {
        assertThatThrownBy(() -> UrlValidator.validate(url)).isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void rejectsMalformedUri() {
        assertThatThrownBy(() -> UrlValidator.validate("ht!tp://not a url"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void rejectsUrlWithoutHost() {
        assertThatThrownBy(() -> UrlValidator.validate("https:///no-host"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost/", "http://127.0.0.1/admin", "http://0.0.0.0/"})
    void rejectsLoopbackAndAnyLocalTargets(String url) {
        assertThatThrownBy(() -> UrlValidator.validate(url)).isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void recognizesReservedPaths() {
        assertThat(UrlValidator.isReservedPath("api")).isTrue();
        assertThat(UrlValidator.isReservedPath("Actuator")).isTrue();
        assertThat(UrlValidator.isReservedPath("my-custom-alias")).isFalse();
    }
}
