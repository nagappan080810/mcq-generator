package com.example.hello.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A single generated MCQ question. This is the unit that gets pushed to the
 * Redis question hash and served back to the client.
 *
 * <p>Stored in Redis as sorted set member (JSON string): {@code {id, question, options, correctAnswer, correctIndexes, area, explanation, source, isMultiSelect, model}}</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GenerationQuestion {

    private String id;
    private String question;
    private boolean isMultiSelect;
    private List<String> options;
    private List<String> correctAnswer;
    private List<Integer> correctIndexes;
    private String area;
    private String explanation;
    private QuestionSource source;
    private String model;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public boolean isMultiSelect() {
        return isMultiSelect;
    }

    public void setMultiSelect(boolean multiSelect) {
        isMultiSelect = multiSelect;
    }

    public List<String> getOptions() {
        return options;
    }

    public void setOptions(List<String> options) {
        this.options = options;
    }

    public List<String> getCorrectAnswer() {
        return correctAnswer;
    }

    public void setCorrectAnswer(List<String> correctAnswer) {
        this.correctAnswer = correctAnswer;
    }

    public List<Integer> getCorrectIndexes() {
        return correctIndexes;
    }

    public void setCorrectIndexes(List<Integer> correctIndexes) {
        this.correctIndexes = correctIndexes;
    }

    public String getArea() {
        return area;
    }

    public void setArea(String area) {
        this.area = area;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public QuestionSource getSource() {
        return source;
    }

    public void setSource(QuestionSource source) {
        this.source = source;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
