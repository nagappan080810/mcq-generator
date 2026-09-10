package com.example.hello.model;

/**
 * Payload for the POST /api/v1/jobs/{jobId}/report endpoint. Used to report
 * an issue with a job (e.g. a bad question discovered by a consumer).
 */
public class JobReport {

    private String reason;
    private String questionId;
    private String details;

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String questionId) {
        this.questionId = questionId;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}
