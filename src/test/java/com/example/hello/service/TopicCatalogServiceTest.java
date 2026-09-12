package com.example.hello.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TopicCatalogServiceTest {

    private final TopicCatalogService service =
            new TopicCatalogService(new ObjectMapper());

    @Test
    void findAreasReturnsAreasForMatchingLevel() {
        assertThat(service.findAreas("java", "Senior-Developer"))
                .containsExactly(
                        "Core Java & OOP",
                        "Collections & Generics",
                        "Concurrency",
                        "JVM Internals & Java 21");
    }

    @Test
    void findAreasMatchesTechnologyCaseInsensitively() {
        assertThat(service.findAreas("Java", "Senior-Developer"))
                .containsExactly(
                        "Core Java & OOP",
                        "Collections & Generics",
                        "Concurrency",
                        "JVM Internals & Java 21");
    }

    @Test
    void findAreasReturnsSingleAreaWhenOnlyOneLevelMatches() {
        assertThat(service.findAreas("react", "Junior-Developer"))
                .containsExactly("Hooks");
    }

    @Test
    void findAreasReturnsEmptyForUnknownTechnology() {
        assertThat(service.findAreas("cobol", "Senior-Developer")).isEmpty();
    }

    @Test
    void findAreasReturnsEmptyForUnknownJobTitle() {
        assertThat(service.findAreas("java", "Principal-Engineer")).isEmpty();
    }
}