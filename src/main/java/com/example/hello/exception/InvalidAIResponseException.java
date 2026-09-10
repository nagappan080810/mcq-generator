package com.example.hello.exception;

/**
 * Thrown when the AI returns a response that cannot be parsed into valid questions.
 * This is a semantic failure (not transient), so Resilience4j will NOT retry it —
 * the circuit breaker will count it as a failure though.
 */
public class InvalidAIResponseException extends RuntimeException {

    public InvalidAIResponseException(String message) {
        super(message);
    }

    public InvalidAIResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
