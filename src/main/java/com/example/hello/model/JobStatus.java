package com.example.hello.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * Job status metadata stored in the Redis hash {@code job:{jobId}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobStatus {

    public enum Status {
        PENDING,      // queued, not yet started
        PROCESSING,   // actively generating questions
        COMPLETED,    // all questions generated
        PARTIAL,      // some questions generated, some failed
        FAILED        // job aborted with an error
    }

    private String jobId;
    private Status status;
    private int totalRecords;
    private int processedCount;
    private int failedCount;
    private String currentStage;
    private Instant startedAt;
    private Instant lastUpdated;
    private String error;
    private String provider;
    private String model;
    private String difficulty;
    private String jobTitle;

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getTotalRecords() {
        return totalRecords;
    }

    public void setTotalRecords(int totalRecords) {
        this.totalRecords = totalRecords;
    }

    public int getProcessedCount() {
        return processedCount;
    }

    public void setProcessedCount(int processedCount) {
        this.processedCount = processedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(int failedCount) {
        this.failedCount = failedCount;
    }

    public String getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(String currentStage) {
        this.currentStage = currentStage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Instant lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public void setJobTitle(String jobTitle) {
        this.jobTitle = jobTitle;
    }

    /** Convert a Redis hash (Map of field -> value strings) into a JobStatus. */
    public static JobStatus fromHash(String jobId, Map<Object, Object> hash) {
        JobStatus status = new JobStatus();
        status.setJobId(jobId);
        if (hash == null) {
            return status;
        }
        status.setStatus(Status.valueOf(str(hash.get("status"))));
        status.setTotalRecords(intOf(hash.get("total_records")));
        status.setProcessedCount(intOf(hash.get("processed_count")));
        status.setFailedCount(intOf(hash.get("failed_count")));
        status.setCurrentStage(str(hash.get("current_stage")));
        status.setStartedAt(instantOf(hash.get("started_at")));
        status.setLastUpdated(instantOf(hash.get("last_updated")));
        status.setError(str(hash.get("error")));
        status.setProvider(str(hash.get("provider")));
        status.setModel(str(hash.get("model")));
        status.setDifficulty(str(hash.get("difficulty")));
        status.setJobTitle(str(hash.get("job_title")));
        return status;
    }

    /** Convert a JobStatus into a Redis hash (Map of field -> string value). */
    @JsonProperty
    public Map<String, String> toHash() {
        return Map.ofEntries(
                Map.entry("status", status == null ? "PENDING" : status.name()),
                Map.entry("total_records", String.valueOf(totalRecords)),
                Map.entry("processed_count", String.valueOf(processedCount)),
                Map.entry("failed_count", String.valueOf(failedCount)),
                Map.entry("current_stage", currentStage == null ? "" : currentStage),
                Map.entry("started_at", startedAt == null ? "" : startedAt.toString()),
                Map.entry("last_updated", lastUpdated == null ? "" : lastUpdated.toString()),
                Map.entry("error", error == null ? "" : error),
                Map.entry("provider", provider == null ? "" : provider),
                Map.entry("model", model == null ? "" : model),
                Map.entry("difficulty", difficulty == null ? "" : difficulty),
                Map.entry("job_title", jobTitle == null ? "" : jobTitle)
        );
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static int intOf(Object o) {
        if (o == null) {
            return 0;
        }
        try {
            return Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Instant instantOf(Object o) {
        if (o == null || o.toString().isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(o.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
