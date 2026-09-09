package org.example.urlshortener.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.urlshortener.model.dto.CreateUrlRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RedirectIntegrationTest {

    @DynamicPropertySource
    static void dataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void redirectsAndRecordsAnalytics() throws Exception {
        String shortCode = createShortUrl("https://example.com/target-page", null, null);

        mockMvc.perform(get("/{code}", shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://example.com/target-page"));

        mockMvc.perform(get("/api/v1/urls/{code}", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clickCount").value(1));

        mockMvc.perform(get("/{code}", shortCode)).andExpect(status().isFound());
        mockMvc.perform(get("/{code}", shortCode)).andExpect(status().isFound());

        mockMvc.perform(get("/api/v1/urls/{code}", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clickCount").value(3));

        // Detail rows are written asynchronously; total click count above is synchronous and
        // already correct, but the breakdown endpoint needs a short, bounded wait.
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/urls/{code}/analytics", shortCode))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalClicks").value(3))
                        .andExpect(jsonPath("$.topReferrers").isNotEmpty()));
    }

    @Test
    void returns404ForUnknownShortCode() throws Exception {
        mockMvc.perform(get("/{code}", "totally-unknown-code"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void returns410ForExpiredShortCode() throws Exception {
        String shortCode = createShortUrl("https://example.com/expiring", null, 1L);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mockMvc.perform(get("/{code}", shortCode))
                        .andExpect(status().isGone())
                        .andExpect(jsonPath("$.error").value("URL_EXPIRED")));
    }

    private String createShortUrl(String longUrl, String alias, Long ttlSeconds) throws Exception {
        CreateUrlRequest request = new CreateUrlRequest(longUrl, alias, ttlSeconds);
        String body = mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("shortCode").asText();
    }
}
