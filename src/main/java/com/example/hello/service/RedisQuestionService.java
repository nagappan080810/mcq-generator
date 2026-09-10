package com.example.hello.service;

import com.example.hello.model.GenerationQuestion;
import com.example.hello.model.JobStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Persistence layer for jobs and questions backed by Redis (Upstash).
 *
 * <pre>
 *   Key: job:{jobId}                              (job tracking hash, TTL = mcq.job-ttl-hours)
 *        status | total_records | processed_count | failed_count |
 *        current_stage | started_at | last_updated | error |
 *        provider | model | difficulty | job_title
 *
 *   Key: {technology}:{difficulty}:{jobTitle}       (question sorted set, NO TTL)
 *        Score: epoch timestamp with milliseconds (numeric, enables ZRANGEBYSCORE)
 *        Member: JSON string with fields:
 *          id, question, options, correctAnswer, correctIndexes,
 *          area, explanation, source, isMultiSelect, model
 * </pre>
 */
@Service
public class RedisQuestionService {

    private static final Logger log = LoggerFactory.getLogger(RedisQuestionService.class);
    private static final String JOB_KEY_PREFIX = "job:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${mcq.job-ttl-hours:24}")
    private int jobTtlHours;

    public RedisQuestionService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // Job tracking (standard Redis HASH)
    // ------------------------------------------------------------------

    public void createJob(JobStatus status) {
        String key = JOB_KEY_PREFIX + status.getJobId();
        redis.opsForHash().putAll(key, status.toHash());
        redis.expire(key, Duration.ofHours(jobTtlHours));
    }

    public JobStatus getJob(String jobId) {
        java.util.Map<Object, Object> hash = redis.opsForHash().entries(JOB_KEY_PREFIX + jobId);
        if (hash.isEmpty()) {
            throw new JobNotFoundException(jobId);
        }
        return JobStatus.fromHash(jobId, hash);
    }

    public void updateJob(JobStatus status) {
        status.setLastUpdated(java.time.Instant.now());
        redis.opsForHash().putAll(JOB_KEY_PREFIX + status.getJobId(), status.toHash());
    }

    public boolean jobExists(String jobId) {
        return Boolean.TRUE.equals(redis.hasKey(JOB_KEY_PREFIX + jobId));
    }

    public void deleteJob(String jobId) {
        redis.delete(JOB_KEY_PREFIX + jobId);
    }

    // ------------------------------------------------------------------
    // Questions (Redis Sorted Set)
    // ------------------------------------------------------------------

    private String questionKey(String technology, String jobTitle, String difficulty) {
        return technology + ":" + difficulty + ":" + jobTitle;
    }

    /**
     * Push a single question into the Redis sorted set using ZADD.
     * Score = epoch milliseconds, Member = JSON string with id and correctIndexes set.
     *
     * @return the question id assigned ({@code {epochMs}_{jobId}})
     */
    public String pushQuestion(String technology, GenerationQuestion question,
                               String jobId, String jobTitle, String difficulty) {
        String key = questionKey(technology, jobTitle, difficulty);
        long score = System.currentTimeMillis();
        String qId = score + "_" + jobId;

        question.setId(qId);
        question.setCorrectIndexes(computeCorrectIndexes(question));

        String json = toJson(question);
        redis.opsForZSet().add(key, json, (double) score);
        log.debug("Pushed question {} (score={}) to sorted set '{}'", qId, score, key);
        return qId;
    }

    /**
     * Retrieve all questions stored under a given composite key, in chronological order.
     */
    public List<GenerationQuestion> getQuestions(String technology, String jobTitle, String difficulty) {
        String key = questionKey(technology, jobTitle, difficulty);
        Set<String> members = redis.opsForZSet().range(key, 0, -1);
        if (members == null) {
            return List.of();
        }
        return members.stream().map(this::fromJson).collect(Collectors.toList());
    }

    /**
     * Retrieve questions as a raw JSON array string.
     */
    public String getQuestionsRaw(String technology, String jobTitle, String difficulty) {
        List<GenerationQuestion> questions = getQuestions(technology, jobTitle, difficulty);
        return toJsonList(questions);
    }

    /**
     * Retrieve the latest N questions (newest first, by descending score).
     */
    public List<GenerationQuestion> getLatestQuestions(String technology, String jobTitle,
                                                       String difficulty, int count) {
        String key = questionKey(technology, jobTitle, difficulty);
        Set<String> members = redis.opsForZSet().reverseRange(key, 0, count - 1);
        if (members == null) {
            return List.of();
        }
        return members.stream().map(this::fromJson).collect(Collectors.toList());
    }

    /**
     * Retrieve questions added since a given epoch millisecond timestamp.
     */
    public List<GenerationQuestion> getQuestionsSince(String technology, String jobTitle,
                                                      String difficulty, long sinceEpochMs) {
        String key = questionKey(technology, jobTitle, difficulty);
        Set<String> members = redis.opsForZSet().rangeByScore(key, (double) sinceEpochMs, Double.MAX_VALUE);
        if (members == null) {
            return List.of();
        }
        return members.stream().map(this::fromJson).collect(Collectors.toList());
    }

    /**
     * Retrieve questions within a time range [fromMs, toMs].
     */
    public List<GenerationQuestion> getQuestionsInRange(String technology, String jobTitle,
                                                        String difficulty, long fromMs, long toMs) {
        String key = questionKey(technology, jobTitle, difficulty);
        Set<String> members = redis.opsForZSet().rangeByScore(key, (double) fromMs, (double) toMs);
        if (members == null) {
            return List.of();
        }
        return members.stream().map(this::fromJson).collect(Collectors.toList());
    }

    /**
     * Retrieve a single question by its id (scan + match on the id field).
     */
    public GenerationQuestion getQuestionById(String technology, String jobTitle,
                                              String difficulty, String questionId) {
        String key = questionKey(technology, jobTitle, difficulty);
        Set<String> members = redis.opsForZSet().range(key, 0, -1);
        if (members == null) {
            return null;
        }
        for (String member : members) {
            GenerationQuestion q = fromJson(member);
            if (questionId.equals(q.getId())) {
                return q;
            }
        }
        return null;
    }

    /**
     * Retrieve the correctAnswer field for a specific question as a raw JSON string.
     */
    public String getCorrectAnswer(String technology, String jobTitle,
                                   String difficulty, String questionId) {
        GenerationQuestion q = getQuestionById(technology, jobTitle, difficulty, questionId);
        if (q == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(q.getCorrectAnswer());
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Serialization helpers
    // ------------------------------------------------------------------

    private List<Integer> computeCorrectIndexes(GenerationQuestion q) {
        if (q.getCorrectAnswer() == null || q.getOptions() == null) {
            return List.of();
        }
        List<Integer> indexes = new ArrayList<>();
        for (String ans : q.getCorrectAnswer()) {
            int idx = q.getOptions().indexOf(ans);
            if (idx >= 0) {
                indexes.add(idx);
            }
        }
        return indexes;
    }

    private String toJson(GenerationQuestion q) {
        try {
            return objectMapper.writeValueAsString(q);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize question", e);
        }
    }

    private String toJsonList(List<GenerationQuestion> questions) {
        try {
            return objectMapper.writeValueAsString(questions);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize question list: {}", e.getMessage());
            return "[]";
        }
    }

    public GenerationQuestion fromJson(String json) {
        try {
            return objectMapper.readValue(json, GenerationQuestion.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize question", e);
        }
    }
}
