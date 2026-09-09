package org.example.urlshortener.service;

import org.example.urlshortener.exception.UrlNotFoundException;
import org.example.urlshortener.model.dto.AnalyticsResponse;
import org.example.urlshortener.model.entity.ClickEvent;
import org.example.urlshortener.model.entity.UrlMapping;
import org.example.urlshortener.repository.ClickEventRepository;
import org.example.urlshortener.repository.UrlMappingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final int LOOKBACK_DAYS = 30;
    private static final int TOP_N = 10;

    private final UrlMappingRepository urlMappingRepository;
    private final ClickEventRepository clickEventRepository;

    public AnalyticsService(UrlMappingRepository urlMappingRepository, ClickEventRepository clickEventRepository) {
        this.urlMappingRepository = urlMappingRepository;
        this.clickEventRepository = clickEventRepository;
    }

    @Transactional(readOnly = true)
    public AnalyticsResponse getAnalytics(String shortCode) {
        // totalClicks is sourced from UrlMapping.clickCount, the synchronous/atomic counter
        // updated on every redirect (see UrlService#recordClick), not from the click_event
        // table below. click_event writes are async and best-effort (see ClickEventService),
        // so the day/referrer/user-agent breakdowns can lag or very rarely undercount relative
        // to totalClicks -- an accepted trade-off documented in docs/RISKS.md.
        UrlMapping mapping = urlMappingRepository.findByShortCodeAndActiveTrue(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
        long totalClicks = mapping.getClickCount();

        Instant since = Instant.now().minusSeconds(LOOKBACK_DAYS * 24L * 3600);
        List<ClickEvent> recentEvents = clickEventRepository.findByShortCodeAndOccurredAtGreaterThanEqual(shortCode, since);

        Map<String, Long> byDay = recentEvents.stream()
                .collect(Collectors.groupingBy(
                        e -> DAY_FORMAT.format(e.getOccurredAt()),
                        LinkedHashMap::new,
                        Collectors.counting()));

        List<AnalyticsResponse.DailyCount> clicksByDay = byDay.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new AnalyticsResponse.DailyCount(e.getKey(), e.getValue()))
                .toList();

        Map<String, Long> topReferrers = toTopMap(clickEventRepository.topReferrers(shortCode));
        Map<String, Long> topUserAgents = toTopMap(clickEventRepository.topUserAgents(shortCode));

        return new AnalyticsResponse(shortCode, totalClicks, clicksByDay, topReferrers, topUserAgents);
    }

    private Map<String, Long> toTopMap(List<Object[]> rows) {
        return rows.stream()
                .limit(TOP_N)
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Long) row[1],
                        (a, b) -> a,
                        LinkedHashMap::new));
    }
}
