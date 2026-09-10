package com.example.hello;

import com.example.hello.config.JacksonConfig;
import com.example.hello.controller.GenerationController;
import com.example.hello.model.GenerationRequest;
import com.example.hello.model.JobStatus;
import com.example.hello.service.JobProcessorService;
import com.example.hello.service.RedisQuestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GenerationController.class)
@Import(JacksonConfig.class)
class GenerationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @MockitoBean
    private RedisQuestionService redisService;

    @MockitoBean
    private JobProcessorService jobProcessorService;

    @Test
    void generateReturnsAcceptedWithJobId() throws Exception {
        GenerationRequest body = new GenerationRequest();
        body.setTechnologies(List.of("Java"));
        body.setDifficulty("Medium");
        body.setJobTitle("Senior Engineer");
        body.setQuestionsPerTech(2);

        mockMvc.perform(post("/api/v1/generate")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").isNotEmpty());
    }

    @Test
    void getStatusReturnsJobStatus() throws Exception {
        JobStatus status = new JobStatus();
        status.setJobId("batch_abc123");
        status.setStatus(JobStatus.Status.COMPLETED);
        status.setProcessedCount(4);
        status.setTotalRecords(4);
        status.setStartedAt(Instant.now());
        when(redisService.getJob(anyString())).thenReturn(status);

        mockMvc.perform(get("/api/v1/jobs/batch_abc123/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("batch_abc123"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.processedCount").value(4));
    }

    @Test
    void getQuestionsReturnsJson() throws Exception {
        String json = "[{\"id\":\"1757520123456_batch_abc123\",\"question\":\"test?\",\"options\":[\"a\",\"b\",\"c\",\"d\"],\"correctAnswer\":[\"b\"],\"correctIndexes\":[1],\"area\":\"Java\",\"explanation\":\"...\",\"source\":\"AI_GENERATED\",\"model\":\"openrouter/free\",\"isMultiSelect\":false}]";
        when(redisService.getQuestionsRaw("Java", "Senior Engineer", "Medium"))
                .thenReturn(json);

        mockMvc.perform(get("/api/v1/questions")
                        .param("technology", "Java")
                        .param("difficulty", "Medium")
                        .param("jobTitle", "Senior Engineer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].question").value("test?"))
                .andExpect(jsonPath("$[0].correctAnswer[0]").value("b"))
                .andExpect(jsonPath("$[0].correctIndexes[0]").value(1))
                .andExpect(jsonPath("$[0].id").value("1757520123456_batch_abc123"));
    }
}
