package org.example.urlshortener.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.example.urlshortener.model.dto.CreateUrlRequest;
import org.example.urlshortener.model.dto.UrlResponse;
import org.example.urlshortener.service.UrlService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/urls")
@Tag(name = "URLs", description = "Create, inspect, list and delete shortened URLs")
public class UrlController {

    private final UrlService urlService;

    public UrlController(UrlService urlService) {
        this.urlService = urlService;
    }

    @PostMapping
    @Operation(summary = "Shorten a URL", description = "Creates a new short URL, optionally with a custom alias and TTL")
    public ResponseEntity<UrlResponse> create(@Valid @RequestBody CreateUrlRequest request, HttpServletRequest httpRequest) {
        UrlResponse response = urlService.createShortUrl(request, baseUrl(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{code}")
    @Operation(summary = "Get metadata for a short code")
    public ResponseEntity<UrlResponse> getMetadata(@PathVariable String code, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(urlService.getMetadata(code, baseUrl(httpRequest)));
    }

    @GetMapping
    @Operation(summary = "List active short URLs, paginated",
            description = "Always ordered newest-first; sorting is not caller-configurable")
    public ResponseEntity<Page<UrlResponse>> list(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    HttpServletRequest httpRequest) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(urlService.listUrls(pageable, baseUrl(httpRequest)));
    }

    @DeleteMapping("/{code}")
    @Operation(summary = "Deactivate a short URL")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        urlService.deleteUrl(code);
        return ResponseEntity.noContent().build();
    }

    private String baseUrl(HttpServletRequest request) {
        StringBuffer url = request.getRequestURL();
        String uri = request.getRequestURI();
        String contextPath = url.substring(0, url.length() - uri.length());
        return contextPath;
    }
}
