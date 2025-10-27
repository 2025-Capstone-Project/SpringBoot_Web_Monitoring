package com.example.demo.model;

import java.time.LocalDateTime;
import java.util.List;

public class Question {
    private Long id;
    private Long userId;
    private String username; // denormalized for convenience
    private String title;
    private String content;
    private String tags; // comma separated
    private String status; // OPEN | RESOLVED
    private Long selectedAnswerId;
    private int views;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // transient
    private List<Answer> answers;

    // getters/setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getSelectedAnswerId() { return selectedAnswerId; }
    public void setSelectedAnswerId(Long selectedAnswerId) { this.selectedAnswerId = selectedAnswerId; }
    public int getViews() { return views; }
    public void setViews(int views) { this.views = views; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<Answer> getAnswers() { return answers; }
    public void setAnswers(List<Answer> answers) { this.answers = answers; }
}

