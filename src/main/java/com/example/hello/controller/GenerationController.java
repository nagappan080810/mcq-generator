package com.example.hello.controller;

import com.example.hello.model.GenerateResponse;
import com.example.hello.model.GenerationQuestion;
import com.example.hello.model.GenerationRequest;
import com.example.hello.model.JobReport;
import com.example.hello.model.JobStatus;
import com.example.hello.service.JobProcessorService;
import com.example.hello.service.JobNotFoundException;
import com.example.hello.service.RedisQuestionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for the MCQ generator service.
 */
@RestController
@RequestMapping("/api/v1")
public class GenerationController {

    private static final Logger log = LoggerFactory.getLogger(GenerationController.class);

    private final RedisQuestionService redisService;
    private final JobProcessorService jobProcessorService;
    private final ObjectMapper objectMapper;

    public GenerationController(RedisQuestionService redisService,
                                JobProcessorService jobProcessorService,
                                ObjectMapper mcqObjectMapper) {
        this.redisService = redisService;
        this.jobProcessorService = jobProcessorService;
        this.objectMapper = mcqObjectMapper;
    }

    /**
     * Start a generation job. Returns the job id immediately; the job runs
     * asynchronously and pushes questions into Redis as they are produced.
     */
    @PostMapping("/generate")
    public ResponseEntity<GenerateResponse> generate(@Valid @RequestBody GenerationRequest request) {
        String jobId = "batch_" + UUID.randomUUID().toString().substring(0, 8);

        JobStatus status = new JobStatus();
        status.setJobId(jobId);
        status.setStatus(JobStatus.Status.PENDING);
        status.setCurrentStage("QUEUED");
        status.setStartedAt(Instant.now());
        status.setLastUpdated(Instant.now());
        status.setProvider(request.getProvider() != null ? request.getProvider() : "openrouter");
        status.setModel(request.getModel() != null ? request.getModel() : "openrouter/free");
        status.setDifficulty(request.getDifficulty());
        status.setJobTitle(request.getJobTitle());
        status.setTotalRecords(request.getTechnologies().size() * request.getQuestionsPerTech());
        redisService.createJob(status);

        // Fire-and-forget async processing
        jobProcessorService.processJob(jobId, request);
        log.info("Job {} queued with {} technologies x {} questions",
                jobId, request.getTechnologies().size(), request.getQuestionsPerTech());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new GenerateResponse(jobId));
    }

    /** Get job status metadata. */
    @GetMapping("/jobs/{jobId}/status")
    public JobStatus getJobStatus(@PathVariable String jobId) {
        return redisService.getJob(jobId);
    }

    /** Get generated questions as a JSON array. Supports filtering by limit, since, from/to. */
    @GetMapping(value = "/questions", produces = "application/json")
    public String getQuestions(
            @RequestParam String technology,
            @RequestParam String difficulty,
            @RequestParam String jobTitle,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long since,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {

        if (since != null) {
            List<GenerationQuestion> questions = redisService.getQuestionsSince(
                    technology, jobTitle, difficulty, since);
            return toJson(questions);
        }
        if (from != null && to != null) {
            List<GenerationQuestion> questions = redisService.getQuestionsInRange(
                    technology, jobTitle, difficulty, from, to);
            return toJson(questions);
        }
        if (limit != null) {
            List<GenerationQuestion> questions = redisService.getLatestQuestions(
                    technology, jobTitle, difficulty, limit);
            return toJson(questions);
        }
        return redisService.getQuestionsRaw(technology, jobTitle, difficulty);
    }

    /** Get a single question by its id (epochMs_jobId) within a composite key. */
    @GetMapping("/questions/{questionId}")
    public ResponseEntity<GenerationQuestion> getQuestion(
            @RequestParam String technology,
            @RequestParam String difficulty,
            @RequestParam String jobTitle,
            @PathVariable String questionId) {
        GenerationQuestion q = redisService.getQuestionById(technology, jobTitle, difficulty, questionId);
        if (q == null) {
            throw new JobNotFoundException(questionId);
        }
        return ResponseEntity.ok(q);
    }

    /** Report an issue with a job (e.g. a bad question). */
    @PostMapping("/jobs/{jobId}/report")
    public ResponseEntity<Map<String, String>> report(@PathVariable String jobId,
                                                      @RequestBody JobReport report) {
        if (!redisService.jobExists(jobId)) {
            throw new JobNotFoundException(jobId);
        }
        log.warn("Job {} reported: reason={}, questionId={}, details={}",
                jobId, report.getReason(), report.getQuestionId(), report.getDetails());
        // For now, just log. Could be extended to write into a report hash.
        return ResponseEntity.ok(Map.of("message", "Report received", "jobId", jobId));
    }

    /** Delete a job tracking hash. Questions are shared across jobs with the
     *  same technology/difficulty/jobTitle and are left untouched. */
    @DeleteMapping("/jobs/{jobId}")
    public ResponseEntity<Map<String, String>> deleteJob(@PathVariable String jobId) {
        if (!redisService.jobExists(jobId)) {
            throw new JobNotFoundException(jobId);
        }
        redisService.deleteJob(jobId);
        return ResponseEntity.ok(Map.of("message", "Job deleted", "jobId", jobId));
    }

    private String toJson(List<GenerationQuestion> questions) {
        try {
            return objectMapper.writeValueAsString(questions);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
