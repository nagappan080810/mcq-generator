package com.example.hello;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HelloController {

    @GetMapping("/hello")
    public Map<String, String> hello() {
        return Map.of(
                "message", "Hello from Spring Boot 4 with GraalVM Native Image!",
                "status", "UP"
        );
    }

    @GetMapping("/")
    public String root() {
        return "Spring Boot 4 Hello World. Try GET /hello";
    }

}
