package com.example.hello.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads {@code tech-interview-topics-by-role.json} once at startup and exposes
 * the interview areas defined for a given technology + job title (seniority level).
 *
 * <p>File shape: {@code technology -> area -> { jobTitle -> [topics] }}.
 * The technology key matches case-insensitively; the job title must be an exact
 * match against the level keys ({@code Junior-Developer}, {@code Mid-level-Developer},
 * {@code Senior-Developer}, {@code Lead}, {@code Architect}).</p>
 */
@Service
public class TopicCatalogService {

    private static final Logger log = LoggerFactory.getLogger(TopicCatalogService.class);

    private static final String RESOURCE_PATH = "tech-interview-topics-by-role.json";

    private final Map<String, Map<String, Map<String, List<String>>>> catalog;

    public TopicCatalogService(ObjectMapper objectMapper) {
        this.catalog = loadCatalog(objectMapper);
        log.info("Loaded interview topic catalog with {} technologies", this.catalog.size());
    }

    /**
     * Find the areas for a technology that contain the given job title (level).
     *
     * @return area names in JSON order, or an empty list when the technology or
     *         job title is not present in the catalog
     */
    public List<String> findAreas(String technology, String jobTitle) {
        if (technology == null || jobTitle == null) {
            return List.of();
        }
        Map<String, Map<String, List<String>>> techAreas = catalog.get(normalize(technology));
        if (techAreas == null) {
            return List.of();
        }
        return techAreas.entrySet().stream()
                .filter(entry -> entry.getValue().containsKey(jobTitle))
                .map(Map.Entry::getKey)
                .toList();
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Map<String, Map<String, List<String>>>> loadCatalog(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
            Map<String, Map<String, Map<String, List<String>>>> parsed =
                    objectMapper.readValue(in, new TypeReference<>() {});
            return parsed.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            entry -> normalize(entry.getKey()),
                            Map.Entry::getValue,
                            (a, b) -> a,
                            java.util.LinkedHashMap::new));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to load interview topic catalog from classpath: " + RESOURCE_PATH, e);
        }
    }
}