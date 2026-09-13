package com.example.hello.service;

import com.example.hello.model.GenerationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BatchGenerationSchedulerTest {

    private static final List<String> JOB_TITLES = List.of(
            "Junior-Developer", "Mid-level-Developer", "Senior-Developer", "Lead", "Architect");
    private static final List<String> TECHNOLOGIES = List.of(
            "java", "core-dsa", "system-design", "react", "react-nextjs", "angular",
            "node-backend", "spring-boot", "auth", "design-patterns", "twelve-factor", "kubernetes");
    private static final List<String> DIFFICULTIES = List.of("Easy", "Medium", "Hard");

    @Mock
    private JobProcessorService jobProcessorService;

    @Mock
    private RedisQuestionService redisQuestionService;

    private BatchGenerationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BatchGenerationScheduler(jobProcessorService, redisQuestionService);
        ReflectionTestUtils.setField(scheduler, "jobTitles", JOB_TITLES);
        ReflectionTestUtils.setField(scheduler, "technologies", TECHNOLOGIES);
        ReflectionTestUtils.setField(scheduler, "difficulties", DIFFICULTIES);
        ReflectionTestUtils.setField(scheduler, "questionsPerTech", 10);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
    }

    @Test
    void dispatchesOneJobPerJobTitleWithAllTechnologiesAndDifficulties() {
        ArgumentCaptor<GenerationRequest> requestCaptor =
                ArgumentCaptor.forClass(GenerationRequest.class);

        scheduler.run();

        verify(jobProcessorService, times(JOB_TITLES.size())).processJob(anyString(), requestCaptor.capture());

        List<GenerationRequest> requests = requestCaptor.getAllValues();
        assertThat(requests).hasSize(JOB_TITLES.size());
        assertThat(requests)
                .extracting(GenerationRequest::getJobTitle)
                .containsExactlyInAnyOrderElementsOf(JOB_TITLES);
        for (GenerationRequest request : requests) {
            assertThat(request.getTechnologies()).containsExactlyElementsOf(TECHNOLOGIES);
            assertThat(request.getDifficulties()).isEqualTo(DIFFICULTIES);
            assertThat(request.getQuestionsPerTech()).isEqualTo(10);
        }
    }

    @Test
    void runDoesNothingWhenDisabled() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);

        scheduler.run();

        verify(jobProcessorService, never()).processJob(anyString(), any(GenerationRequest.class));
    }

    @Test
    void runDoesNothingWhenListsAreEmpty() {
        ReflectionTestUtils.setField(scheduler, "jobTitles", List.of());

        scheduler.run();

        verify(jobProcessorService, never()).processJob(anyString(), any(GenerationRequest.class));
    }
}