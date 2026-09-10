package com.example.hello.service;

import com.example.hello.exception.InvalidAIResponseException;
import com.example.hello.model.GenerationQuestion;
import com.example.hello.model.GenerationRequest;
import com.example.hello.model.QuestionSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates Spring AI (OpenRouter-compatible OpenAI endpoint) to generate
 * MCQ questions following the mcq-generator agent spec.
 *
 * <p>Each technology gets exactly ONE AI call. Resilience4j handles network-level
 * retries (5s delay, 3 attempts) and circuit breaker protection. If the AI
 * returns invalid JSON, an {@link InvalidAIResponseException} is thrown — this
 * is NOT retried by Resilience4j (semantic failure, not transient).</p>
 */
@Service
public class McqGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(McqGeneratorService.class);

    private static final String SYSTEM_PROMPT = """
            You are **mcq-generator**, a question-generation engine for a technical MCQ quiz app.
            You produce high-quality interview MCQs in the style of a rapid technical grill.
            You never render a quiz yourself and you never converse beyond the single JSON payload described below.

            ## Task
            - For EVERY technology in `technologies`, generate exactly `questionsPerTech` MCQs.
            - Distribute questions across the `areasByTechnology` entries for that technology. If `areasByTechnology` is not provided, choose 2-4 relevant areas for the technology yourself.
            - Total output = technologies.length * questionsPerTech questions.
            - Align difficulty to `difficulty` (Easy = fundamentals; Medium = working/intermediate; Hard = advanced boundary facts).
            - Align depth to `jobTitle` (Junior -> surface; Senior -> deeper reasoning; Architect -> trade-offs/production constraints).
            - Vary the position of the correct option(s); do not form a repeating pattern.
              For single-select: one correct answer among exactly 4 options.
              For multi-select: exactly 4 options with 2-3 correct options, and label the question clearly: "Select ALL that apply."
            - Keep each question short and focused on ONE concept (rapid-grill style).
            - Prefer modern/current APIs and best practices.

            ## Output rule (CRITICAL)
            The FIRST character of your response MUST be `[` and the LAST character MUST be `]`.
            Respond with **ONLY a single valid JSON array** — no markdown fences, no prose before or after,
            no commentary, no code block wrappers, no safety checks, no greetings.
            If you cannot comply, respond with a JSON object: {"error": "short reason"}.

            Each element must have exactly this shape:
            {
              "question": "Which statement about Java records is true?",
              "isMultiSelect": false,
              "options": ["a", "b", "c", "d"],
              "correctAnswer": ["b"],
              "area": "Core Java & OOP",
              "explanation": "One or two plain sentences explaining why the correct answer(s) are right."
            }

            Field rules:
            - `options` must always be an array of exactly 4 strings.
            - `correctAnswer` must contain the actual correct answer text(s), matching entries in `options`.
              Length 1 -> single-select. Length 2-3 -> multi-select; such questions must contain "Select ALL that apply." in the `question` text.
            - `explanation` must be 1-2 sentences, layman-friendly but precise.
            - `area` must be one of the areas provided in the session input, or any relevant area of your choosing when none are provided.
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    @Value("${mcq.provider:openrouter}")
    private String defaultProvider;

    @Value("${mcq.default-model:openrouter/free}")
    private String defaultModel;

    @Value("${mcq.temperature:0.7}")
    private double defaultTemperature;

    public McqGeneratorService(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    /**
     * Generate questions for one technology within a job.
     * Resilience4j handles network retries and circuit breaker protection.
     * Internally retries up to 3 attempts when the AI returns non-JSON responses
     * (e.g. safety checks, greetings, or other prose instead of the expected array).
     *
     * @return list of GenerationQuestion records parsed from the AI output
     */
    @CircuitBreaker(name = "aiProvider", fallbackMethod = "generateFallback")
    @Retry(name = "aiProvider")
    public List<GenerationQuestion> generateForTechnology(
            GenerationRequest request,
            String technology) {

        String resolvedModel = resolveModel(request.getModel());
        log.info("Generating {} questions for technology '{}' with model '{}'",
                request.getQuestionsPerTech(), technology, resolvedModel);

        Map<String, Object> session = new HashMap<>();
        session.put("technologies", List.of(technology));
        session.put("difficulty", request.getDifficulty());
        session.put("jobTitle", request.getJobTitle());
        session.put("questionsPerTech", request.getQuestionsPerTech());
        Map<String, List<String>> areasByTech = request.getAreasByTechnology();
        if (areasByTech != null) {
            List<String> techAreas = areasByTech.get(technology);
            if (techAreas != null && !techAreas.isEmpty()) {
                session.put("areasByTechnology", Map.of(technology, techAreas));
            }
        }
        
        session.put("existingQuestions",
                request.getExistingQuestions() == null ? List.of() : request.getExistingQuestions());

        // Build prompt once — reused across parse-retry attempts
        String userPrompt = serializeSession(session);

        log.info("Session payload for {}: {}", technology, userPrompt);
        Prompt prompt = buildPrompt(userPrompt, request.getTemperature(), resolvedModel);

        int maxParseAttempts = 3;
        for (int attempt = 1; attempt <= maxParseAttempts; attempt++) {
            ChatResponse response = chatModel.call(prompt);
            String raw = response.getResult().getOutput().getText();

            if (raw == null) {
                if (attempt < maxParseAttempts) {
                    log.warn("AI returned null for {}, retrying (attempt {}/{})",
                            technology, attempt, maxParseAttempts);
                    continue;
                }
                throw new InvalidAIResponseException(
                        "AI returned null response for technology: " + technology);
            }
            

            log.warn("Raw AI response for {} (attempt {}, first 500 chars): {}",
                    technology, attempt,
                    raw.length() > 500 ? raw.substring(0, 500) : raw);

            try {
                String cleaned = cleanOutput(raw);
                JsonNode json = objectMapper.readTree(cleaned);

                if (json == null || (json.isObject() && json.has("error")) || !json.isArray()) {
                    if (attempt < maxParseAttempts) {
                        log.warn("AI returned error/non-array for {}, retrying (attempt {}/{})",
                                technology, attempt, maxParseAttempts);
                        continue;
                    }
                    throw new InvalidAIResponseException(
                            "AI returned error or non-array: " + cleaned);
                }

                List<GenerationQuestion> questions = parsePayload(json, resolvedModel);
                if (questions.isEmpty()) {
                    if (attempt < maxParseAttempts) {
                        log.warn("AI returned no valid questions for {}, retrying (attempt {}/{})",
                                technology, attempt, maxParseAttempts);
                        continue;
                    }
                    throw new InvalidAIResponseException(
                            "AI returned no valid questions for technology: " + technology);
                }
                log.info("Parsed {} questions for '{}' — question texts: {}",
                    questions.size(), technology,
                    questions.stream().map(q -> q.getQuestion()).toList());

                log.info("Successfully parsed {} questions for technology '{}' (attempt {})",
                        questions.size(), technology, attempt);
                return questions;

            } catch (InvalidAIResponseException | JsonProcessingException  e) {
                if (attempt >= maxParseAttempts) {
                    throw new InvalidAIResponseException(
                            "AI returned invalid JSON after " + maxParseAttempts + " attempts: " + e.getMessage(), e);
                }
                log.warn("Parse failed for {}, retrying (attempt {}/{}): {}",
                        technology, attempt, maxParseAttempts, e.getMessage());
            }
        }

        throw new InvalidAIResponseException(
                "AI returned no valid response after " + maxParseAttempts +
                " attempts for technology: " + technology);
    }

    /**
     * Fallback when circuit breaker is open or all retries exhausted.
     * Re-throws as IllegalStateException so the job processor records the failure.
     */
    private List<GenerationQuestion> generateFallback(
            GenerationRequest request, String technology, Throwable t) {
        log.warn("AI provider unavailable for technology {}: {}", technology, t.getMessage());
        throw new IllegalStateException("AI provider unavailable for " + technology + ": " + t.getMessage(), t);
    }

    private String resolveModel(String requestModel) {
        return requestModel != null && !requestModel.isBlank() ? requestModel : defaultModel;
    }

    private Prompt buildPrompt(String userPrompt, Double temperature, String model) {
        var options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature != null ? temperature : defaultTemperature)
                .build();
        return new Prompt(List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(userPrompt)), options);
    }

    private String serializeSession(Map<String, Object> session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize session input", e);
        }
    }

    /**
     * Clean the raw AI output: strip markdown fences and anything outside the
     * outermost JSON array brackets.
     */
    private String cleanOutput(String raw) {
        String s = raw.trim();
        s = s.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)\\s*```$", "");
        s = s.trim();
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        if (s.startsWith("{")) {
            return s; // valid JSON error object — caller's check handles it
        }
        throw new InvalidAIResponseException(
                "No JSON array found. First 200 chars: " +
                (s.length() > 200 ? s.substring(0, 200) : s));
    }

    private List<GenerationQuestion> parsePayload(JsonNode payload, String model) {
        List<GenerationQuestion> questions = new ArrayList<>();
        for (JsonNode node : payload) {
            try {
                GenerationQuestion q = objectMapper.treeToValue(node, GenerationQuestion.class);
                q.setModel(model);
                q.setSource(QuestionSource.AI_GENERATED);
                if (q.getQuestion() == null || q.getOptions() == null || q.getOptions().size() != 4) {
                    log.warn("Skipping malformed question element: {}", node);
                    continue;
                }
                questions.add(q);
            } catch (JsonProcessingException e) {
                log.warn("Skipping malformed question element: {}", node);
            }
        }
        return questions;
    }
}
