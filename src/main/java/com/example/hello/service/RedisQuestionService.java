package com.example.hello.service;

import com.example.hello.model.GenerationQuestion;
import com.example.hello.model.JobStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
   *          area, explanation, source, isMultiSelect, model, difficulty
 *
 *   Key: dedup:{technology}:{difficulty}:{jobTitle}  (SET of SHA-256 (base64url) question fingerprints, NO TTL)
 *        Populated lazily (backfilled from the sorted set) and used as an O(1)
 *        duplicate check before a question is pushed. Each member is a
 *        fingerprint of the normalized question stem plus its sorted options,
 *        so two questions match only when stem AND choices are identical.
 * </pre>
 */
@Service
public class RedisQuestionService {

    private static final Logger log = LoggerFactory.getLogger(RedisQuestionService.class);
    private static final String JOB_KEY_PREFIX = "job:";
    private static final String DEDUP_KEY_PREFIX = "dedup:";
    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final String CANONICAL_SEPARATOR = "\n";
    private static final String CANONICAL_PREFIX = "Q" + CANONICAL_SEPARATOR;

    /**
     * Atomically records a question fingerprint in the dedup SET and, only when it is new,
     * appends it to the question sorted set. Returns 1 when pushed, 0 when duplicate.
     *
     * <pre>
     *   KEYS[1] = dedup SET key
     *   KEYS[2] = question sorted set key
     *   ARGV[1] = question fingerprint (base64url SHA-256 of stem + sorted options)
     *   ARGV[2] = score (epoch millis)
     *   ARGV[3] = full JSON member
     * </pre>
     */
    private static final DefaultRedisScript<Long> DEDUP_ADD_SCRIPT = new DefaultRedisScript<>("""
            local added = redis.call('SADD', KEYS[1], ARGV[1])
            if added == 1 then
                redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])
            end
            return added
            """, Long.class);

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

    private String dedupKey(String technology, String jobTitle, String difficulty) {
        return DEDUP_KEY_PREFIX + questionKey(technology, jobTitle, difficulty);
    }

    /**
     * Push a single question into the Redis sorted set using ZADD.
     * Score = epoch milliseconds, Member = JSON string with id and correctIndexes set.
     *
     * <p>Before pushing, the question is checked against the dedup SET via its
     * fingerprint (SHA-256 of the normalized stem plus sorted options, O(1)).
     * If it already exists — from a previous job — the push is skipped.
     * The check and the ZADD happen atomically in a single Lua script, so
     * concurrent jobs cannot both push the same question.</p>
     *
     * @return {@code true} if the question was pushed (new), {@code false} if it was
     *         skipped as a duplicate
     */
    public boolean pushQuestion(String technology, GenerationQuestion question,
                                String jobId, String jobTitle, String difficulty) {
        String key = questionKey(technology, jobTitle, difficulty);
        long score = System.currentTimeMillis();
        String qId = score + "_" + jobId;

        question.setId(qId);
        question.setCorrectIndexes(computeCorrectIndexes(question));

        String json = toJson(question);
        Long added = redis.execute(DEDUP_ADD_SCRIPT,
                List.of(dedupKey(technology, jobTitle, difficulty), key),
                fingerprint(question), String.valueOf(score), json);

        boolean pushed = added != null && added == 1L;
        if (pushed) {
            log.debug("Pushed question {} (score={}) to sorted set '{}'", qId, score, key);
        } else {
            log.info("Skipped duplicate question (score={}) for sorted set '{}': {}",
                    score, key, question.getQuestion());
        }
        return pushed;
    }

    /**
     * Seed the dedup SET for a composite key.
     *
     * <p>On first call for a key it backfills the fingerprints of any questions
     * already stored in the sorted set (one-time O(N) pass, so legacy data is
     * deduped too). Each fingerprint covers the normalized stem plus the sorted
     * options, so the same question with shuffled choices is treated as a
     * duplicate.</p>
     */
    public void ensureQuestionDedupSeeded(String technology, String jobTitle, String difficulty) {
        String dKey = dedupKey(technology, jobTitle, difficulty);
        if (!Boolean.TRUE.equals(redis.hasKey(dKey))) {
            Set<String> members = redis.opsForZSet().range(
                    questionKey(technology, jobTitle, difficulty), 0, -1);
            if (members != null && !members.isEmpty()) {
                redis.opsForSet().add(dKey, members.stream()
                        .map(this::fromJson)
                        .map(RedisQuestionService::fingerprint)
                        .toArray(String[]::new));
                log.debug("Backfilled dedup set '{}' with {} existing question fingerprints",
                        dKey, members.size());
            }
        }
    }

    private static String normalizeDedupText(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Build a compact duplicate-detection fingerprint for a question.
     *
     * <p>The canonical input is the normalized stem plus each option normalized
     * and sorted alphabetically, so two questions match only when their stem and
     * their full set of choices are identical (independent of choice order or
     * case). The digest is a base64url-encoded SHA-256 (43 chars) to keep Redis
     * set members small.</p>
     */
    private static String fingerprint(GenerationQuestion q) {
        String stem = normalizeDedupText(q.getQuestion());
        String options = Stream.concat(
                        Stream.of(stem),
                        q.getOptions() == null ? Stream.<String>of() : q.getOptions().stream()
                                .map(RedisQuestionService::normalizeDedupText)
                                .sorted(Comparator.naturalOrder()))
                .collect(Collectors.joining(CANONICAL_SEPARATOR));
        return sha256Base64(CANONICAL_PREFIX + options);
    }

    private static String sha256Base64(String input) {
        try {
            byte[] digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
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
