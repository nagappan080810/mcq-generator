package com.example.hello.service;

import com.example.hello.model.GenerationRequest;
import com.example.hello.model.JobStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Hourly background scheduler that regenerates questions for the full set of
 * job titles.
 *
 * <p>For each job title one async job is dispatched carrying ALL technologies
 * and ALL difficulties, so the difficulty axis lives inside the request (the AI
 * labels each generated question) instead of being expanded into scheduler
 * combos. Each job reuses {@link JobProcessorService} (retries, circuit breaker,
 * Redis push, per-job failure isolation).</p>
 */
@Service
public class BatchGenerationScheduler {

    private static final Logger log = LoggerFactory.getLogger(BatchGenerationScheduler.class);

    private final JobProcessorService jobProcessorService;

    private final RedisQuestionService redisQuestionService;

    @Value("${mcq.scheduler.enabled:true}")
    private boolean enabled;

    @Value("${mcq.scheduler.questions-per-tech:10}")
    private int questionsPerTech;

    @Value("${mcq.scheduler.job-titles:}")
    private List<String> jobTitles;

    @Value("${mcq.scheduler.technologies:}")
    private List<String> technologies;

    @Value("${mcq.scheduler.difficulties:}")
    private List<String> difficulties;

    public BatchGenerationScheduler(JobProcessorService jobProcessorService, RedisQuestionService redisQuestionService) {
        this.jobProcessorService = jobProcessorService;
        this.redisQuestionService = redisQuestionService;
    }

    @Scheduled(initialDelayString = "${mcq.scheduler.initial-delay-ms:60000}",
            fixedDelayString = "${mcq.scheduler.fixed-delay-ms:3600000}")
    public void run() {
        if (!enabled) {
            return;
        }
        if (jobTitles.isEmpty() || technologies.isEmpty() || difficulties.isEmpty()) {
            log.warn("Scheduled sweep has no job titles / technologies / difficulties configured");
            return;
        }
        log.info("Started scheduler sweep over {} job titles (all technologies x difficulties bundled)",
                jobTitles.size());
        for (String jobTitle : jobTitles) {
            dispatch(jobTitle);
        }
    }

    private void dispatch(String jobTitle) {
        GenerationRequest request = new GenerationRequest();
        request.setTechnologies(technologies);
        request.setDifficulties(difficulties);
        request.setJobTitle(jobTitle);
        request.setQuestionsPerTech(questionsPerTech);

        String jobId = "sched_" + UUID.randomUUID().toString().substring(0, 8);
        JobStatus status = new JobStatus();
        status.setJobId(jobId);
        status.setStatus(JobStatus.Status.PENDING);
        status.setCurrentStage("QUEUED");
        status.setProvider("openrouter");
        status.setModel("openrouter/free");
        status.setDifficulty(String.join(", ", difficulties));
        status.setJobTitle(jobTitle);
        status.setTotalRecords(technologies.size() * difficulties.size() * questionsPerTech);
        status.setProcessedCount(0);
        status.setFailedCount(0);
        status.setStartedAt(java.time.Instant.now());
        status.setLastUpdated(java.time.Instant.now());
        redisQuestionService.createJob(status);
        jobProcessorService.processJob(jobId, request);
        log.info("Scheduled job {} queued for {} ({} technologies, {} difficulties)",
                jobId, jobTitle, technologies.size(), difficulties.size());
    }
}