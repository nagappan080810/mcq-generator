package com.example.hello.service;

import com.example.hello.model.GenerationQuestion;
import com.example.hello.model.GenerationRequest;
import com.example.hello.model.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobProcessorServiceTest {

    @Mock
    private McqGeneratorService mcqGeneratorService;

    @Mock
    private RedisQuestionService redisService;

    private JobProcessorService processor;

    @BeforeEach
    void setUp() {
        processor = new JobProcessorService(mcqGeneratorService, redisService);
    }

    private GenerationQuestion question(String text) {
        GenerationQuestion q = new GenerationQuestion();
        q.setQuestion(text);
        q.setDifficulty("Medium");
        return q;
    }

    @Test
    void countsDuplicatesAndOnlyUniquePushesAsProcessed() {
        GenerationRequest request = new GenerationRequest();
        request.setTechnologies(List.of("Java"));
        request.setJobTitle("Senior Engineer");
        request.setQuestionsPerTech(2);
        request.setExistingQuestions(List.of("What is Java?"));

        JobStatus status = new JobStatus();
        status.setJobId("job_1");

        when(redisService.getJob("job_1")).thenReturn(status);
        when(mcqGeneratorService.generateForTechnology(request, "Java"))
                .thenReturn(List.of(question("What is Spring?"), question("What is Java?")));
        when(redisService.pushQuestion(eq("Java"), any(), eq("job_1"), eq("Senior Engineer"), eq("Medium")))
                .thenReturn(true, false);

        processor.processJob("job_1", request);

        verify(redisService).ensureQuestionDedupSeeded(
                "Java", "Senior Engineer", "Medium");

        ArgumentCaptor<JobStatus> statusCaptor = ArgumentCaptor.forClass(JobStatus.class);
        verify(redisService, atLeastOnce()).updateJob(statusCaptor.capture());
        JobStatus finalStatus = statusCaptor.getAllValues().get(statusCaptor.getAllValues().size() - 1);
        assertThat(finalStatus.getProcessedCount()).isEqualTo(1);
        assertThat(finalStatus.getDuplicateCount()).isEqualTo(1);
        assertThat(finalStatus.getStatus()).isEqualTo(JobStatus.Status.COMPLETED);
        assertThat(finalStatus.getCurrentStage()).isEqualTo("DONE");
    }

    @Test
    void recordsZeroDuplicatesWhenAllUnique() {
        GenerationRequest request = new GenerationRequest();
        request.setTechnologies(List.of("Java"));
        request.setJobTitle("Senior Engineer");
        request.setQuestionsPerTech(1);

        JobStatus status = new JobStatus();
        status.setJobId("job_2");

        when(redisService.getJob("job_2")).thenReturn(status);
        when(mcqGeneratorService.generateForTechnology(request, "Java"))
                .thenReturn(List.of(question("What is Spring?")));
        when(redisService.pushQuestion(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        processor.processJob("job_2", request);

        ArgumentCaptor<JobStatus> statusCaptor = ArgumentCaptor.forClass(JobStatus.class);
        verify(redisService, atLeastOnce()).updateJob(statusCaptor.capture());
        JobStatus finalStatus = statusCaptor.getAllValues().get(statusCaptor.getAllValues().size() - 1);
        assertThat(finalStatus.getProcessedCount()).isEqualTo(1);
        assertThat(finalStatus.getDuplicateCount()).isZero();
        assertThat(finalStatus.getStatus()).isEqualTo(JobStatus.Status.COMPLETED);
    }
}