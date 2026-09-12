package com.example.hello.service;

import com.example.hello.model.GenerationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hourly background scheduler that regenerates questions for the full
 * matrix of job titles x technologies x difficulties.
 *
 * <p>Instead of regenerating everything every hour (180 AI calls/tick), it runs
 * every hour but only regenerates a deterministic slice of the matrix: the full
 * set of combos is covered exactly once per day. Each combo produces
 * {@code mcq.scheduler.questions-per-tech} questions and is dispatched as its own
 * async job, reusing {@link JobProcessorService} (retries, circuit breaker, Redis
 * push, per-combo failure isolation).</p>
 */
@Service
public class BatchGenerationScheduler {

    private static final Logger log = LoggerFactory.getLogger(BatchGenerationScheduler.class);

    private static final long HOUR_MS = 3_600_000L;

    private final JobProcessorService jobProcessorService;

    @Value("${mcq.scheduler.enabled:true}")
    private boolean enabled;

    @Value("${mcq.scheduler.daily-slices:24}")
    private int dailySlices;

    @Value("${mcq.scheduler.questions-per-tech:10}")
    private int questionsPerTech;

    @Value("${mcq.scheduler.job-titles:}")
    private List<String> jobTitles;

    @Value("${mcq.scheduler.technologies:}")
    private List<String> technologies;

    @Value("${mcq.scheduler.difficulties:}")
    private List<String> difficulties;

    public BatchGenerationScheduler(JobProcessorService jobProcessorService) {
        this.jobProcessorService = jobProcessorService;
    }

    @Scheduled(initialDelayString = "${mcq.scheduler.initial-delay-ms:60000}",
            fixedDelayString = "${mcq.scheduler.fixed-delay-ms:3600000}")
    public void run() {
        if (!enabled) {
            return;
        }
        List<Combo> combos = buildCombos();
        if (combos.isEmpty()) {
            log.warn("Scheduled sweep has no job title / technology / difficulty combos configured");
            return;
        }
        int slices = Math.max(1, dailySlices);
        long hourIndex = System.currentTimeMillis() / HOUR_MS;
        int runIndex = (int) (hourIndex % slices);
        runSlice(runIndex, combos, slices);
    }

    /**
     * Dispatches the deterministic slice of the combo matrix for a given run index.
     * Slice boundaries are chosen so that across {@code slices} consecutive runs
     * every combo is covered exactly once, with no gaps or overlaps.
     */
    protected void runSlice(int runIndex, List<Combo> combos, int slices) {
        int start = runIndex * combos.size() / slices;
        int end = (runIndex + 1) * combos.size() / slices;
        List<Combo> slice = combos.subList(start, end);

        log.info("Scheduled sweep: run {} of {} handling {} of {} combos (questions/tech={})",
                runIndex + 1, slices, slice.size(), combos.size(), questionsPerTech);
        for (Combo combo : slice) {
            dispatch(combo);
        }
    }

    private void dispatch(Combo combo) {
        GenerationRequest request = new GenerationRequest();
        request.setTechnologies(List.of(combo.technology()));
        request.setJobTitle(combo.jobTitle());
        request.setDifficulty(combo.difficulty());
        request.setQuestionsPerTech(questionsPerTech);

        String jobId = "sched_" + UUID.randomUUID().toString().substring(0, 8);
        jobProcessorService.processJob(jobId, request);
        log.info("Scheduled job {} queued for {} / {} / {}", jobId,
                combo.jobTitle(), combo.technology(), combo.difficulty());
    }

    /**
     * Builds the combination matrix in job title -> technology -> difficulty order.
     */
    protected List<Combo> buildCombos() {
        List<Combo> combos = new ArrayList<>();
        for (String jobTitle : jobTitles) {
            for (String technology : technologies) {
                for (String difficulty : difficulties) {
                    combos.add(new Combo(jobTitle, technology, difficulty));
                }
            }
        }
        return combos;
    }

    /** A single (jobTitle, technology, difficulty) generation unit. */
    protected record Combo(String jobTitle, String technology, String difficulty) {
    }
}