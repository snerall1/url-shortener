package org.example.urlshortener.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.urlshortener.model.dto.AnalyticsResponse;
import org.example.urlshortener.service.AnalyticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/urls")
@Tag(name = "Analytics", description = "Click analytics for a short URL")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/{code}/analytics")
    @Operation(summary = "Get click analytics for a short code")
    public ResponseEntity<AnalyticsResponse> getAnalytics(@PathVariable String code) {
        return ResponseEntity.ok(analyticsService.getAnalytics(code));
    }
}
