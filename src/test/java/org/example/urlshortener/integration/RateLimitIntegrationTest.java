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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.rate-limit.capacity=2",
        "app.rate-limit.refill-per-second=0.01"
})
class RateLimitIntegrationTest {

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
    void returns429AfterBucketIsExhausted() throws Exception {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/rate-limited", null, null);
        String body = objectMapper.writeValueAsString(request);
        // Distinct client key (the registry is a singleton shared across test methods in this
        // class) so this test's bucket exhaustion cannot bleed into other tests.
        String clientIp = "203.0.113.10";

        mockMvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Forwarded-For", clientIp))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Forwarded-For", clientIp))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Forwarded-For", clientIp))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertNotNull(
                        result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)));
    }

    @Test
    void redirectEndpointIsNotRateLimited() throws Exception {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/many-redirects", null, null);
        String body = objectMapper.writeValueAsString(request);
        // Distinct client key, see note above.
        String clientIp = "203.0.113.20";
        String response = mockMvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Forwarded-For", clientIp))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String shortCode = objectMapper.readTree(response).get("shortCode").asText();

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/{code}", shortCode))
                    .andExpect(status().isFound());
        }
    }
}
