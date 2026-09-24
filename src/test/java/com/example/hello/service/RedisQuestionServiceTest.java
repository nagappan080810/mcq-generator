package com.example.hello.service;

import com.example.hello.model.GenerationQuestion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisQuestionServiceTest {

    private static final String DEDUP_KEY = "dedup:Java:Medium:Senior Engineer";
    private static final String QUESTION_KEY = "Java:Medium:Senior Engineer";

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ZSetOperations<String, String> zSetOps;

    @Mock
    private SetOperations<String, String> setOps;

    private RedisQuestionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new RedisQuestionService(redis, objectMapper);
    }

    private GenerationQuestion question(String text) {
        GenerationQuestion q = new GenerationQuestion();
        q.setQuestion(text);
        q.setOptions(List.of("a", "b", "c", "d"));
        q.setCorrectAnswer(List.of("b"));
        return q;
    }

    private static String fingerprint(String text) {
        try {
            String canonical = "Q\n" + text.trim().toLowerCase(java.util.Locale.ROOT) + "\n"
                    + List.of("a", "b", "c", "d").stream()
                            .map(s -> s.trim().toLowerCase(java.util.Locale.ROOT))
                            .sorted()
                            .collect(java.util.stream.Collectors.joining("\n"));
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void pushQuestionReturnsTrueWhenLuaReportsAdded() {
        when(redis.execute(any(RedisScript.class), anyList(),
                any(), any(), any())).thenReturn(1L);

        boolean pushed = service.pushQuestion("Java", question("  What Is Java?  "),
                "job_1", "Senior Engineer", "Medium");

        assertThat(pushed).isTrue();
        verify(redis, never()).opsForZSet();

        ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(redis).execute(scriptCaptor.capture(), keysCaptor.capture(),
                argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture());

        assertThat(scriptCaptor.getValue().getScriptAsString())
                .containsIgnoringCase("SADD")
                .containsIgnoringCase("ZADD");
        assertThat(keysCaptor.getValue()).containsExactly(DEDUP_KEY, QUESTION_KEY);
        List<Object> args = argsCaptor.getAllValues();
        assertThat(args).hasSize(3);
        assertThat(args.get(0)).isEqualTo(fingerprint("  What Is Java?  "));
        assertThat(args.get(0).toString()).matches("[A-Za-z0-9_-]{43}");
        assertThat(args.get(1).toString()).matches("\\d+");
        assertThat((String) args.get(2)).contains("\"question\":\"  What Is Java?  \"");
    }

    @Test
    void pushQuestionReturnsFalseAndSkipsWhenDuplicate() {
        when(redis.execute(any(RedisScript.class), anyList(),
                any(), any(), any())).thenReturn(0L);

        boolean pushed = service.pushQuestion("Java", question("What is Java?"),
                "job_1", "Senior Engineer", "Medium");

        assertThat(pushed).isFalse();
        verify(redis, never()).opsForZSet();

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keysCaptor.capture(),
                any(), any(), any());
        assertThat(keysCaptor.getValue()).containsExactly(DEDUP_KEY, QUESTION_KEY);
    }

    @Test
    void ensureDedupSeededBackfillsExistingSortedSetWithFingerprints() throws Exception {
        when(redis.hasKey(DEDUP_KEY)).thenReturn(false);
        when(redis.opsForZSet()).thenReturn(zSetOps);
        String memberJson = objectMapper.writeValueAsString(question("  What Is Java?  "));
        when(zSetOps.range(QUESTION_KEY, 0, -1)).thenReturn(Set.of(memberJson));
        when(redis.opsForSet()).thenReturn(setOps);

        service.ensureQuestionDedupSeeded("Java", "Senior Engineer", "Medium");

        ArgumentCaptor<String[]> valuesCaptor = ArgumentCaptor.forClass(String[].class);
        verify(setOps).add(eq(DEDUP_KEY), valuesCaptor.capture());
        assertThat(valuesCaptor.getValue()).containsExactly(fingerprint("  What Is Java?  "));
    }

    @Test
    void ensureDedupSeededSkipsBackfillWhenSetExists() {
        when(redis.hasKey(DEDUP_KEY)).thenReturn(true);

        service.ensureQuestionDedupSeeded("Java", "Senior Engineer", "Medium");

        verify(zSetOps, never()).range(anyString(), eq(0L), eq(-1L));
        verify(setOps, never()).add(eq(DEDUP_KEY), any(String[].class));
    }
}