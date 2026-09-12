package com.example.hello.service;

import com.example.hello.model.GenerationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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

    private BatchGenerationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BatchGenerationScheduler(jobProcessorService);
        ReflectionTestUtils.setField(scheduler, "jobTitles", JOB_TITLES);
        ReflectionTestUtils.setField(scheduler, "technologies", TECHNOLOGIES);
        ReflectionTestUtils.setField(scheduler, "difficulties", DIFFICULTIES);
        ReflectionTestUtils.setField(scheduler, "dailySlices", 24);
        ReflectionTestUtils.setField(scheduler, "questionsPerTech", 10);
    }

    @Test
    void buildCombosReturnsFullMatrixInRoleFirstOrder() {
        List<BatchGenerationScheduler.Combo> combos = scheduler.buildCombos();
        assertThat(combos).hasSize(5 * 12 * 3);
        assertThat(combos.get(0).jobTitle()).isEqualTo("Junior-Developer");
        assertThat(combos.get(0).technology()).isEqualTo("java");
        assertThat(combos.get(0).difficulty()).isEqualTo("Easy");
        assertThat(combos.get(combos.size() - 1).jobTitle()).isEqualTo("Architect");
        assertThat(combos.get(combos.size() - 1).difficulty()).isEqualTo("Hard");
    }

    @Test
    void dailySlicesCoverEveryComboExactlyOnce() {
        List<BatchGenerationScheduler.Combo> combos = scheduler.buildCombos();
        Map<String, Integer> dispatchCount = new HashMap<>();
        org.mockito.stubbing.Answer<Void> record = invocation -> {
            GenerationRequest request = invocation.getArgument(1);
            dispatchCount.merge(
                    request.getJobTitle() + "|" + request.getTechnologies().get(0) + "|" + request.getDifficulty(),
                    1, Integer::sum);
            return null;
        };
        org.mockito.Mockito.doAnswer(record).when(jobProcessorService).processJob(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(GenerationRequest.class));

        for (int run = 0; run < 24; run++) {
            scheduler.runSlice(run, combos, 24);
        }

        assertThat(dispatchCount).hasSize(combos.size());
        assertThat(dispatchCount.values()).allMatch(count -> count == 1);
    }

    @Test
    void dispatchedRequestIsConfiguredCorrectly() {
        ArgumentCaptor<GenerationRequest> requestCaptor =
                ArgumentCaptor.forClass(GenerationRequest.class);

        scheduler.runSlice(0, scheduler.buildCombos(), 24);

        verify(jobProcessorService, times(7)).processJob(
                org.mockito.ArgumentMatchers.anyString(), requestCaptor.capture());
        GenerationRequest request = requestCaptor.getAllValues().get(0);
        assertThat(request.getTechnologies()).containsExactly("java");
        assertThat(request.getJobTitle()).isEqualTo("Junior-Developer");
        assertThat(request.getDifficulty()).isEqualTo("Easy");
        assertThat(request.getQuestionsPerTech()).isEqualTo(10);
    }

    @Test
    void runDoesNothingWhenDisabled() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);

        scheduler.run();

        verify(jobProcessorService, never()).processJob(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(GenerationRequest.class));
    }
}