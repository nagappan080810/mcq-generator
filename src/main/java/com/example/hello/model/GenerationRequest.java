package com.example.hello.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * The POST /api/v1/generate request payload.
 *
 * <p>All provider/model fields are optional and fall back to configuration
 * defaults, giving per-request flexibility to switch models without deploying
 * code changes.</p>
 */
@Component 
public class GenerationRequest {

    private String sessionId;

    @NotEmpty
    private List<@NotBlank String> technologies;

    @NotBlank
    private String difficulty;

    @NotBlank
    private String jobTitle;

    @NotNull
    @Min(1)
    private Integer questionsPerTech;

    private Map<String, List<String>> areasByTechnology;

    private List<String> existingQuestions;

    /** Provider name, e.g. "openrouter", "openai", "anthropic". Defaults to configured value. */
    private String provider;

    /** Model id, e.g. "openrouter/free". Defaults to configured value. */
    private String model;

    /** Optional temperature override. */
    private Double temperature;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public List<String> getTechnologies() {
        return technologies;
    }

    public void setTechnologies(List<String> technologies) {
        this.technologies = technologies;
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

    public Integer getQuestionsPerTech() {
        return questionsPerTech;
    }

    public void setQuestionsPerTech(Integer questionsPerTech) {
        this.questionsPerTech = questionsPerTech;
    }

    public Map<String, List<String>> getAreasByTechnology() {
        return areasByTechnology;
    }

    public void setAreasByTechnology(Map<String, List<String>> areasByTechnology) {
        this.areasByTechnology = areasByTechnology;
    }

    public List<String> getExistingQuestions() {
        return existingQuestions;
    }

    public void setExistingQuestions(List<String> existingQuestions) {
        this.existingQuestions = existingQuestions;
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

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }
}
