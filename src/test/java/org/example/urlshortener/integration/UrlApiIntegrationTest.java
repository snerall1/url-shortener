package org.example.urlshortener.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.urlshortener.model.dto.CreateUrlRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UrlApiIntegrationTest {

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
    void createGetListAndDeleteFullLifecycle() throws Exception {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/some/long/path?x=1", null, null);

        String responseBody = mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").isNotEmpty())
                .andExpect(jsonPath("$.longUrl").value("https://example.com/some/long/path?x=1"))
                .andExpect(jsonPath("$.customAlias").value(false))
                .andReturn().getResponse().getContentAsString();

        String shortCode = objectMapper.readTree(responseBody).get("shortCode").asText();

        mockMvc.perform(get("/api/v1/urls/{code}", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value(shortCode));

        mockMvc.perform(get("/api/v1/urls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        mockMvc.perform(delete("/api/v1/urls/{code}", shortCode))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/urls/{code}", shortCode))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void createsWithCustomAlias() throws Exception {
        String alias = "my-alias-" + UUID.randomUUID().toString().substring(0, 8);
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/custom", alias, null);

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value(alias))
                .andExpect(jsonPath("$.customAlias").value(true));
    }

    @Test
    void rejectsDuplicateCustomAlias() throws Exception {
        String alias = "dup-alias-" + UUID.randomUUID().toString().substring(0, 8);
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/first", alias, null);

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        CreateUrlRequest duplicate = new CreateUrlRequest("https://example.com/second", alias, null);
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicate)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ALIAS_CONFLICT"));
    }

    @Test
    void rejectsInvalidLongUrl() throws Exception {
        CreateUrlRequest request = new CreateUrlRequest("javascript:alert(1)", null, null);

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_URL"));
    }

    @Test
    void rejectsBlankLongUrlWithValidationError() throws Exception {
        CreateUrlRequest request = new CreateUrlRequest("", null, null);

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void returns404ForUnknownShortCode() throws Exception {
        mockMvc.perform(get("/api/v1/urls/{code}", "does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listIgnoresUnrecognizedQueryParamsAndReturnsOk() throws Exception {
        mockMvc.perform(get("/api/v1/urls").param("sort", "string"))
                .andExpect(status().isOk());
    }

    @Test
    void listOrdersUrlsNewestFirst() throws Exception {
        CreateUrlRequest first = new CreateUrlRequest("https://example.com/first-created", null, null);
        String firstCode = objectMapper.readTree(mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("shortCode").asText();

        CreateUrlRequest second = new CreateUrlRequest("https://example.com/second-created", null, null);
        String secondCode = objectMapper.readTree(mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("shortCode").asText();

        mockMvc.perform(get("/api/v1/urls").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].shortCode").value(secondCode))
                .andExpect(jsonPath("$.content[1].shortCode").value(firstCode));
    }
}
