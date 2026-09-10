package com.example.hello.service;

import com.example.hello.model.GenerationQuestion;
import com.example.hello.model.GenerationRequest;
import com.example.hello.model.JobStatus;
import com.example.hello.model.QuestionSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Processes a generation job asynchronously. The REST layer returns the job id
 * immediately; this service runs in the background (via {@code @Async}) and
 * pushes each generated question into the Redis question hash as it is produced.
 */
@Service
public class JobProcessorService {

    private static final Logger log = LoggerFactory.getLogger(JobProcessorService.class);

    private final McqGeneratorService mcqGeneratorService;
    private final RedisQuestionService redisService;

    public JobProcessorService(McqGeneratorService mcqGeneratorService, RedisQuestionService redisService) {
        this.mcqGeneratorService = mcqGeneratorService;
        this.redisService = redisService;
    }

    @Async("jobTaskExecutor")
    public void processJob(String jobId, GenerationRequest request) {
        JobStatus status = redisService.getJob(jobId);
        status.setStatus(JobStatus.Status.PROCESSING);
        status.setCurrentStage("INITIALIZING");
        redisService.updateJob(status);

        int totalQuestions = request.getTechnologies().size() * request.getQuestionsPerTech();
        status.setTotalRecords(totalQuestions);

        int processed = 0;
        int failed = 0;

        try {
            for (String technology : request.getTechnologies()) {
                status.setCurrentStage("GENERATING:" + technology);
                redisService.updateJob(status);

                try {
                    List<GenerationQuestion> generated =
                            mcqGeneratorService.generateForTechnology(request, technology);

                    for (GenerationQuestion q : generated) {
                        redisService.pushQuestion(
                                technology, q, jobId,
                                request.getJobTitle(), request.getDifficulty());
                    }
                    processed += generated.size();
                    status.setProcessedCount(processed);
                    status.setCurrentStage("PUSHED:" + technology);
                    redisService.updateJob(status);
                } catch (Exception e) {
                    failed++;
                    String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Failed to generate technology {} for job {}: {}", technology, jobId, errorMsg, e);
                    status.setError(errorMsg);
                    status.setFailedCount(failed);
                    redisService.updateJob(status);
                }
            }

            status.setProcessedCount(processed);
            status.setFailedCount(failed);
            status.setStatus(failed == 0 ? JobStatus.Status.COMPLETED : JobStatus.Status.PARTIAL);
            status.setCurrentStage("DONE");
            redisService.updateJob(status);
        } catch (Exception e) {
            log.error("Job {} failed", jobId, e);
            status.setStatus(JobStatus.Status.FAILED);
            status.setError(e.getMessage());
            status.setCurrentStage("FAILED");
            redisService.updateJob(status);
        }
    }
}
