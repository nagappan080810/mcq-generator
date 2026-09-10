package com.example.hello;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class HelloApplicationTests {

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    private ChatModel chatModel;

    @Test
    void contextLoads() {
    }

}
