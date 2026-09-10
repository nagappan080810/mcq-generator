package com.example.hello.model;

public class GenerateResponse {

    private String jobId;

    public GenerateResponse() {
    }

    public GenerateResponse(String jobId) {
        this.jobId = jobId;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }
}
