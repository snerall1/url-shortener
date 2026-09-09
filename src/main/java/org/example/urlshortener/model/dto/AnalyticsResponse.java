package org.example.urlshortener.model.dto;

import java.util.List;
import java.util.Map;

public class AnalyticsResponse {

    private final String shortCode;
    private final long totalClicks;
    private final List<DailyCount> clicksByDay;
    private final Map<String, Long> topReferrers;
    private final Map<String, Long> topUserAgents;

    public AnalyticsResponse(String shortCode, long totalClicks, List<DailyCount> clicksByDay,
                              Map<String, Long> topReferrers, Map<String, Long> topUserAgents) {
        this.shortCode = shortCode;
        this.totalClicks = totalClicks;
        this.clicksByDay = clicksByDay;
        this.topReferrers = topReferrers;
        this.topUserAgents = topUserAgents;
    }

    public String getShortCode() {
        return shortCode;
    }

    public long getTotalClicks() {
        return totalClicks;
    }

    public List<DailyCount> getClicksByDay() {
        return clicksByDay;
    }

    public Map<String, Long> getTopReferrers() {
        return topReferrers;
    }

    public Map<String, Long> getTopUserAgents() {
        return topUserAgents;
    }

    public record DailyCount(String day, long count) {
    }
}
