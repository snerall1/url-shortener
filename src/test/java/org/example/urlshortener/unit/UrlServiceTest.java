package org.example.urlshortener.unit;

import org.example.urlshortener.exception.AliasConflictException;
import org.example.urlshortener.exception.InvalidUrlException;
import org.example.urlshortener.exception.UrlExpiredException;
import org.example.urlshortener.exception.UrlNotFoundException;
import org.example.urlshortener.model.dto.CreateUrlRequest;
import org.example.urlshortener.model.dto.UrlResponse;
import org.example.urlshortener.model.entity.UrlMapping;
import org.example.urlshortener.repository.UrlMappingRepository;
import org.example.urlshortener.service.CachedUrlLookupService;
import org.example.urlshortener.service.ShortCodeGenerator;
import org.example.urlshortener.service.UrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

    @Mock
    private UrlMappingRepository repository;
    @Mock
    private ShortCodeGenerator shortCodeGenerator;
    @Mock
    private CachedUrlLookupService cachedUrlLookupService;

    private UrlService urlService;

    @BeforeEach
    void setUp() {
        urlService = new UrlService(repository, shortCodeGenerator, cachedUrlLookupService);
    }

    @Test
    void createsShortUrlWithGeneratedCode() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/very/long/path", null, null);

        UrlMapping placeholder = new UrlMapping("~pending~temp", request.getLongUrl(), false, Instant.now(), null);
        setId(placeholder, 7L);
        when(repository.saveAndFlush(any(UrlMapping.class))).thenReturn(placeholder);
        when(shortCodeGenerator.generate(7L)).thenReturn("abc123");
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        UrlResponse response = urlService.createShortUrl(request, "http://short.ly");

        assertThat(response.getShortCode()).isEqualTo("abc123");
        assertThat(response.getShortUrl()).isEqualTo("http://short.ly/abc123");
        assertThat(response.isCustomAlias()).isFalse();
    }

    @Test
    void createsShortUrlWithCustomAliasWhenAvailable() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", "my-alias", null);
        when(repository.existsByShortCode("my-alias")).thenReturn(false);
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        UrlResponse response = urlService.createShortUrl(request, "http://short.ly");

        assertThat(response.getShortCode()).isEqualTo("my-alias");
        assertThat(response.isCustomAlias()).isTrue();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsCustomAliasAlreadyInUse() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", "taken", null);
        when(repository.existsByShortCode("taken")).thenReturn(true);

        assertThatThrownBy(() -> urlService.createShortUrl(request, "http://short.ly"))
                .isInstanceOf(AliasConflictException.class);
    }

    @Test
    void rejectsReservedCustomAlias() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", "actuator", null);

        assertThatThrownBy(() -> urlService.createShortUrl(request, "http://short.ly"))
                .isInstanceOf(InvalidUrlException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsInvalidLongUrl() {
        CreateUrlRequest request = new CreateUrlRequest("not-a-valid-url", null, null);

        assertThatThrownBy(() -> urlService.createShortUrl(request, "http://short.ly"))
                .isInstanceOf(InvalidUrlException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void resolveForRedirectThrowsWhenCodeUnknown() {
        when(cachedUrlLookupService.findActiveByShortCode("missing")).thenReturn(null);

        assertThatThrownBy(() -> urlService.resolveForRedirect("missing"))
                .isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    void resolveForRedirectThrowsWhenExpired() {
        UrlMapping expired = new UrlMapping("code1", "https://example.com", false,
                Instant.now().minusSeconds(120), Instant.now().minusSeconds(60));
        when(cachedUrlLookupService.findActiveByShortCode("code1")).thenReturn(expired);

        assertThatThrownBy(() -> urlService.resolveForRedirect("code1"))
                .isInstanceOf(UrlExpiredException.class);
    }

    @Test
    void resolveForRedirectReturnsMappingWhenValid() {
        UrlMapping mapping = new UrlMapping("code1", "https://example.com", false, Instant.now(), null);
        when(cachedUrlLookupService.findActiveByShortCode("code1")).thenReturn(mapping);

        UrlMapping result = urlService.resolveForRedirect("code1");

        assertThat(result.getLongUrl()).isEqualTo("https://example.com");
    }

    @Test
    void deleteMarksMappingInactiveInsteadOfHardDeleting() {
        UrlMapping mapping = new UrlMapping("code1", "https://example.com", false, Instant.now(), null);
        when(repository.findByShortCodeAndActiveTrue("code1")).thenReturn(Optional.of(mapping));

        urlService.deleteUrl("code1");

        assertThat(mapping.isActive()).isFalse();
        verify(repository).save(mapping);
        verify(repository, never()).delete(any());
        verify(repository, never()).deleteById(any());
    }

    private void setId(UrlMapping mapping, long id) {
        try {
            var field = UrlMapping.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(mapping, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
