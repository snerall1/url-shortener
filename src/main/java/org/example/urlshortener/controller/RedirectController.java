package org.example.urlshortener.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.example.urlshortener.model.entity.UrlMapping;
import org.example.urlshortener.service.ClickEventService;
import org.example.urlshortener.service.UrlService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * The hot path of the service. Kept deliberately thin: resolve from cache, fire the async
 * analytics write, atomically bump the counter, and redirect. No blocking I/O beyond the
 * (cached) DB lookup.
 */
@RestController
@Tag(name = "Redirect", description = "Public redirect endpoint")
public class RedirectController {

    private final UrlService urlService;
    private final ClickEventService clickEventService;

    public RedirectController(UrlService urlService, ClickEventService clickEventService) {
        this.urlService = urlService;
        this.clickEventService = clickEventService;
    }

    @GetMapping("/{code}")
    @Operation(summary = "Redirect to the long URL for a short code")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        UrlMapping mapping = urlService.resolveForRedirect(code);

        urlService.recordClick(code);
        clickEventService.recordAsync(
                code,
                request.getHeader(HttpHeaders.REFERER),
                request.getHeader(HttpHeaders.USER_AGENT),
                request.getRemoteAddr());

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(mapping.getLongUrl()))
                .build();
    }
}
