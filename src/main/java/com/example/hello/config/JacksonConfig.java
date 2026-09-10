package com.example.hello.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a dedicated Jackson 2 {@link ObjectMapper} for the MCQ generator's
 * JSON (de)serialization. Spring Boot 4's auto-configured mapper is the
 * Jackson 3 (tools.jackson) flavor; this one is used explicitly by the
 * generator's services so the model annotations resolve deterministically.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper mcqObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
